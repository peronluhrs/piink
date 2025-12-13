#include <jni.h>
#include <string>
#include <android/log.h>
#include <sstream>
#include <dlfcn.h> 

#define TAG "PiinkProto_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_piink_proto_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    std::stringstream ss;
    ss << "--- BRAQUAGE DE LIBRAIRIES CIBLÉ ---\n\n";

    // Liste précise basée sur ta recherche ADB
    // Note : Pour les fichiers dans des sous-dossiers, il faut le chemin absolu.
    const char* libs_to_check[] = {
            // CIBLES PRIORITAIRES (D'après ton scan)
            "/vendor/lib64/camera/components/libdepthmapwrapper.so",
            "libsomc_camerapal.so", 
            "/vendor/lib64/libsomc_camerapal.so",
            "/vendor/lib64/camera/components/com.qti.node.depth.so",
            
            // CIBLES SECONDAIRES
            "libcamera2ndk_vendor.so", // Version vendor du NDK camera
            "/vendor/lib64/camera/components/com.qti.node.swregistration.so"
    };

    bool found = false;

    for (const char* libName : libs_to_check) {
        // TENTATIVE DE CHARGEMENT
        void* handle = dlopen(libName, RTLD_LAZY);

        if (handle) {
            ss << "✅ VICTOIRE ! Chargé : \n" << libName << "\n";
            ss << "   -> Adresse : " << handle << "\n\n";
            found = true;
            
            // ICI : On pourrait chercher des symboles (fonctions) spécifiques
            // dlsym(handle, "init_depth_engine");
            
            dlclose(handle); 
        } else {
            // On affiche juste le nom pour garder la lisibilité
            ss << "❌ Échec : " << libName << "\n";
            // ss << "   Raison : " << dlerror() << "\n"; // Décommenter pour voir l'erreur précise
        }
    }

    if (found) {
        ss << "\n>>> NOUS SOMMES DANS LE SYSTÈME. <<<";
    } else {
        ss << "\nTout est verrouillé. Il faudra peut-être rooter ou utiliser l'API Camera2 différemment.";
    }

    return env->NewStringUTF(ss.str().c_str());
}
