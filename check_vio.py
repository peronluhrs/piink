import re
import os

# Le chemin exact où vous avez mis le fichier log.txt
LOG_PATH = "/home/vincent/Bureau/projets/piink/app/src/main/java/com/piink/proto/log.txt"

def analyze_vio_log():
    print(f"Lecture du fichier : {LOG_PATH}")

    if not os.path.exists(LOG_PATH):
        print(f"ERREUR : Le fichier n'existe pas à cet emplacement.")
        return

    timestamps = []
    # Regex pour capturer les timestamps (ex: 4185460044500)
    regex = re.compile(r"timestamp:\s+(\d+)\s+ns")

    with open(LOG_PATH, 'r') as f:
        for line in f:
            match = regex.search(line)
            if match:
                # Conversion ns -> secondes
                t_sec = int(match.group(1)) / 1e9
                timestamps.append(t_sec)

    if len(timestamps) < 2:
        print("Pas assez de données pour calculer les FPS.")
        return

    # Calcul des écarts
    deltas = [timestamps[i+1] - timestamps[i] for i in range(len(timestamps)-1)]
    avg_delta = sum(deltas) / len(deltas)
    fps = 1.0 / avg_delta if avg_delta > 0 else 0

    print(f"\n--- RÉSULTATS ---")
    print(f"Images analysées : {len(timestamps)}")
    print(f"Délai moyen entre images : {avg_delta:.4f} sec")
    print(f"Fréquence Caméra (FPS)   : {fps:.2f} Hz")

    if fps < 15:
        print("\n[!] PROBLÈME DÉTECTÉ : La caméra est trop lente (10 FPS).")
        print("    Le VIO a besoin de 30 FPS minimum pour s'initialiser correctement.")
        print("    Solution : Vérifiez la configuration de la caméra (Camera2 API) pour forcer 30 FPS.")
    else:
        print("\n[OK] La fréquence semble correcte.")

if __name__ == "__main__":
    analyze_vio_log()
