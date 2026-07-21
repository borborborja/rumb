# Rumb

**Rumb** es un grabador y visor de actividades GPS para Android con **HUD configurable en vivo**,
pensado para ciclismo, running, senderismo y esquí. El nombre viene de la *línea de rumbo*
(loxodromia): el rumbo constante que sigues sobre el mapa.

- 🛰️ **Motor de grabación propio**: filtrado GPS (precisión, warm-up, anti-jitter, anti-saltos),
  barómetro para el desnivel, auto-pausa, sensores BLE (FC · cadencia · potencia) y recuperación
  tras crash (Room). Lógica de filtrado derivada de [OpenTracks](https://github.com/OpenTracksApp/OpenTracks) (Apache-2.0, ver `NOTICE`).
- 🗺️ **Mapas OSM e ICGC** (Catalunya) online **y offline** (MBTiles + descarga de sectores),
  motor **MapLibre GL Native**.
- 📊 **HUD configurable por widgets** (editor WYSIWYG arrastrando sobre la vista real) + pantalla
  «Dades» tipo tiles, también editable en vivo.
- 🧭 **Seguimiento de rutas** con aviso de fuera-de-ruta, zoom adaptativo en giros, orientación
  norte/rumbo y avisos de voz multiidioma.
- 📈 **Gestor de rutas y entrenamientos**: carpetas, ordenar/filtrar (distancia, fecha, municipio,
  dificultad, tipo), tipos de actividad (predefinidos + personalizados), estadísticas con gráficas
  apiladas (altitud/velocidad/FC) y scrubber sincronizado con el mapa.
- 🌍 **i18n**: 18 idiomas (English base + ca, es, fr, de, it, pt, nl, pl, cs, da, el, fi, ro,
  ru, sv, tr, uk) — añadir un idioma = una carpeta `values-xx/` (paridad: `scripts/check_i18n.py`).
- ☁️ **Subida a [Endurain](https://github.com/endurain-project/endurain)** con cola offline.
- 🔌 **Modo companion opcional**: si abres Rumb desde el dashboard de OpenTracks
  (`de.dennisguse.opentracks`), visualiza esa grabación en vivo (Dashboard API, protocolos 1-3).

## Arquitectura

Dos caras en el mismo APK (`applicationId = cat.rumb.app`):

| Cara | Punto de entrada | Descripción |
|------|------------------|-------------|
| **Visor** | `viewer.MapViewerActivity` | Mapa MapLibre + HUD + grabación nativa + seguimiento. También lo lanza OpenTracks vía Dashboard API. |
| **Gestión** | `manager.ManagerActivity` | Launcher. Gestor de rutas/entrenamientos, editores de HUD y Dades, capas, mapas offline, ajustes. |

```
data/recording/   Motor nativo: TrackRecorder (puro, testeado), RecordingService (foreground),
                  GpsSource, PressureSource, AutoPause, BLE (ble/).
data/tracks/      Room (migraciones a mano): biblioteca de rutas/entrenamientos, tipos de actividad, dificultad,
                  ordenación/filtrado, stats y decimación, backfill Nominatim (municipio).
data/opentracks/  Contrato con OpenTracks (Dashboard API) — companion opcional.
data/map/         Fuentes (OSM/ICGC), estilos, MBTiles offline, descarga de sectores.
data/gpx/         GPX/KML/KMZ/TCX (DOM; Android y JVM) con extensiones gpxtpx (FC/cad/potencia).
data/endurain/    Cliente + cola de subida (WorkManager).
data/geo/         Nominatim (reverse geocoding, política OSM 1 req/s).
viewer/           MapLibreController, hud/ (métricas + overlay), data/ (Dades), follow/, audio/.
manager/          Navegación + pantallas Compose Material 3.
```

## Descargar

APK firmada en [Releases](https://github.com/borborborja/rumb/releases/latest) —
se instala sobre la versión anterior sin desinstalar. Proceso de publicación: `DEPLOY.md`.

## Compilar

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug        # APK en app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # tests JVM (motor, GPX, stats, orden/filtro)
```

Requisitos: JDK 17, Android SDK (compileSdk 35). AGP 8.7 · Kotlin 2.0 · MapLibre 11.

## Licencia
Apache-2.0 (ver `LICENSE`/`NOTICE`). Contiene código derivado de OpenTracks y OSMDashboard
(Apache-2.0). Datos de mapa: © OpenStreetMap contributors (ODbL), © Institut Cartogràfic i
Geològic de Catalunya (ICGC, CC-BY). Geocodificación: Nominatim © OpenStreetMap contributors.
