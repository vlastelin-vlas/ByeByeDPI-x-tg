#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>

#include "byedpi/error.h"

static pthread_once_t tunnel_once = PTHREAD_ONCE_INIT;
static int (*tunnel_main)(const unsigned char *, unsigned int, int);
static void (*tunnel_quit)(void);
static void (*set_dns_route)(int (*)(int, const char *, int, const char *, int));
static JavaVM *route_vm;
static jobject route_service;
static jmethodID route_method;
static pthread_t route_thread;
static pthread_mutex_t route_submit_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t route_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t route_ready = PTHREAD_COND_INITIALIZER;
static pthread_cond_t route_done = PTHREAD_COND_INITIALIZER;
static int route_thread_started;
static int route_stopping;
static int route_pending;
static int route_complete;
static int route_result;
static struct {
    int protocol;
    char source[64];
    int source_port;
    char destination[64];
    int destination_port;
} route_request;

static void *route_worker(void *unused) {
    JNIEnv *env;
    if ((*route_vm)->AttachCurrentThread(route_vm, &env, NULL) != JNI_OK) {
        pthread_mutex_lock(&route_lock);
        route_stopping = 1;
        pthread_cond_broadcast(&route_done);
        pthread_mutex_unlock(&route_lock);
        return NULL;
    }
    pthread_mutex_lock(&route_lock);
    for (;;) {
        while (!route_pending && !route_stopping) pthread_cond_wait(&route_ready, &route_lock);
        if (route_stopping) break;
        int protocol = route_request.protocol;
        int source_port = route_request.source_port;
        int destination_port = route_request.destination_port;
        char source[64], destination[64];
        memcpy(source, route_request.source, sizeof(source));
        memcpy(destination, route_request.destination, sizeof(destination));
        pthread_mutex_unlock(&route_lock);

        int result = 0;
        jstring src = (*env)->NewStringUTF(env, source);
        jstring dst = (*env)->NewStringUTF(env, destination);
        if (src && dst)
            result = (*env)->CallIntMethod(env, route_service, route_method,
                                           protocol, src, source_port, dst, destination_port);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            result = 0;
        }
        if (src) (*env)->DeleteLocalRef(env, src);
        if (dst) (*env)->DeleteLocalRef(env, dst);

        pthread_mutex_lock(&route_lock);
        route_result = result;
        route_pending = 0;
        route_complete = 1;
        pthread_cond_signal(&route_done);
    }
    pthread_mutex_unlock(&route_lock);
    (*route_vm)->DetachCurrentThread(route_vm);
    return NULL;
}

static int route_dns(int protocol, const char *source, int source_port,
                     const char *destination, int destination_port) {
    if (!route_thread_started || strlen(source) >= sizeof(route_request.source) ||
        strlen(destination) >= sizeof(route_request.destination)) return 0;
    pthread_mutex_lock(&route_submit_lock);
    pthread_mutex_lock(&route_lock);
    while (route_pending && !route_stopping) pthread_cond_wait(&route_done, &route_lock);
    if (route_stopping) {
        pthread_mutex_unlock(&route_lock);
        pthread_mutex_unlock(&route_submit_lock);
        return 0;
    }
    route_request.protocol = protocol;
    route_request.source_port = source_port;
    route_request.destination_port = destination_port;
    strcpy(route_request.source, source);
    strcpy(route_request.destination, destination);
    route_complete = 0;
    route_pending = 1;
    pthread_cond_signal(&route_ready);
    while (!route_complete && !route_stopping) pthread_cond_wait(&route_done, &route_lock);
    int result = route_stopping ? 0 : route_result;
    pthread_mutex_unlock(&route_lock);
    pthread_mutex_unlock(&route_submit_lock);
    return result;
}

static void load_tunnel(void) {
    void *library = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        LOG(LOG_S, "failed to load tunnel: %s", dlerror());
        return;
    }

    tunnel_main = (int (*)(const unsigned char *, unsigned int, int)) dlsym(library, "hev_socks5_tunnel_main_from_str");
    tunnel_quit = (void (*)(void)) dlsym(library, "hev_socks5_tunnel_quit");
    set_dns_route = (void (*)(int (*)(int, const char *, int, const char *, int)))
        dlsym(library, "hev_socks5_tunnel_set_dns_route_callback");
    if (!set_dns_route) LOG(LOG_S, "DNS route callback entry point unavailable: %s", dlerror());
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_TProxyService_startTunnel(JNIEnv *env, __attribute__((unused)) jobject thiz, jstring config, jint fd) {
    pthread_once(&tunnel_once, load_tunnel);
    if (!tunnel_main || !tunnel_quit) {
        LOG(LOG_S, "tunnel entry points unavailable");
        return -1;
    }

    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    if (!config_str) {
        return -1;
    }
    unsigned int config_len = (unsigned int) (*env)->GetStringUTFLength(env, config);
    if (set_dns_route) {
        jclass service_class = (*env)->GetObjectClass(env, thiz);
        route_method = (*env)->GetMethodID(env, service_class, "routeDns",
            "(ILjava/lang/String;ILjava/lang/String;I)I");
        (*env)->DeleteLocalRef(env, service_class);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            route_method = NULL;
            LOG(LOG_S, "DNS route JNI method unavailable");
        }
        if (route_method && (*env)->GetJavaVM(env, &route_vm) == JNI_OK) {
            route_service = (*env)->NewGlobalRef(env, thiz);
            route_stopping = 0;
            route_pending = 0;
            route_complete = 0;
            route_thread_started = pthread_create(&route_thread, NULL, route_worker, NULL) == 0;
            if (route_thread_started) (*set_dns_route)(route_dns);
        }
    }
    int result = tunnel_main((const unsigned char *) config_str, config_len, fd);
    if (set_dns_route) (*set_dns_route)(NULL);
    if (route_thread_started) {
        pthread_mutex_lock(&route_lock);
        route_stopping = 1;
        pthread_cond_broadcast(&route_ready);
        pthread_cond_broadcast(&route_done);
        pthread_mutex_unlock(&route_lock);
        pthread_join(route_thread, NULL);
        route_thread_started = 0;
    }
    if (route_service) (*env)->DeleteGlobalRef(env, route_service);
    route_service = NULL;
    route_method = NULL;
    route_vm = NULL;
    (*env)->ReleaseStringUTFChars(env, config, config_str);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_romanvht_byedpi_core_TProxyService_stopTunnel(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    pthread_once(&tunnel_once, load_tunnel);
    if (tunnel_quit) {
        tunnel_quit();
    }
}
