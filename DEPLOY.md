# Deploy — cómo se publica Rumb

Guía única del proceso de release. Si el proceso cambia, cambia ESTE fichero en el mismo
commit — `AGENTS.md` y cualquier agente remiten aquí.

## Descargar la app

- **Última versión estable**: <https://github.com/borborborja/rumb/releases/latest>
  → asset `Rumb-vX.Y.Z.apk` (APK firmada; instala sobre la anterior sin desinstalar).
- Todas las versiones: <https://github.com/borborborja/rumb/releases>.
- La APK se firma siempre con la misma clave (keystore en secrets de CI), así que las
  actualizaciones son in-place. Una APK compilada en local va firmada con la clave *debug*
  y **no** instalará sobre una release publicada.

## Publicar una release

1. **Verificar** (el workflow de APK no ejecuta tests):
   ```bash
   ./gradlew :app:testGithubDebugUnitTest
   python3 scripts/check_i18n.py        # si se tocaron strings
   ```
2. **Bump de versión** en `app/build.gradle.kts` — las DOS líneas, en el mismo commit que
   cierra el trabajo o en uno propio:
   - `versionCode`: +1 siempre (entero monótono; Android lo exige para actualizar).
   - `versionName`: `X.Y.Z` — mismo número que llevará el tag, sin la `v`.
3. **Commit y push** a `main` (estilo `Módulo: descripción`).
4. **Tag y push del tag** — esto es lo que dispara la publicación:
   ```bash
   git tag -a vX.Y.Z -m "Rumb X.Y.Z"
   git push origin main vX.Y.Z
   ```
5. CI (`.github/workflows/release.yml`) hace el resto: compila `assembleGithubRelease` firmada,
   renombra a `Rumb-vX.Y.Z.apk` y publica el **GitHub Release** con notas autogeneradas.
   Tarda ~5-6 min. Verifica en <https://github.com/borborborja/rumb/actions> que el run
   del tag acaba en verde y que el release tiene la APK adjunta.

### Coherencia de versiones (la regla de oro)

`versionName` (gradle) == tag sin la `v` == nombre del asset. El workflow nombra la APK a
partir del tag (`Rumb-${GITHUB_REF_NAME}.apk`), así que un tag que no coincida con el
`versionName` publica una APK mal etiquetada. Antes de taguear, comprueba:

```bash
grep versionName app/build.gradle.kts   # debe decir X.Y.Z del tag que vas a crear
```

## Build de prueba (sin publicar release)

Para probar en el móvil antes de publicar: el mismo workflow tiene disparo manual, que
compila y firma la APK pero la deja como **artifact** (sin crear release):

```bash
gh workflow run release.yml --ref main
# al acabar (~6 min):
gh run download --name Rumb-apk        # o desde la pestaña Actions → Artifacts
```

Requiere `gh` autenticado con scopes `repo` + `workflow`.

## Preparar el Android App Bundle para Google Play

Google Play recibe el **AAB de la variante `play`**. La variante `github` conserva la
distribución directa por APK. Ambas usan `cat.rumb.app`: no añadas un sufijo al applicationId
si deben seguir siendo la misma app y actualizar sus datos existentes.

La preparación inicial es **1.90.1 (versionCode 172)**, con `compileSdk` y `targetSdk` 36,
AGP 8.10.1 y MapLibre 11.7.0. La variante `play` no contiene el actualizador de APK de
GitHub ni el permiso `REQUEST_INSTALL_PACKAGES`; Google Play entrega sus actualizaciones.
También excluye el servidor LAN y la pantalla de modo escritorio, además de NanoHTTPD:
el escritorio actual transmite ubicación y datos de actividad/perfil por HTTP. Solo podrá
incorporarse a Play cuando su transporte esté cifrado. La variante `github` conserva esta
función y todos sus assets sin cambios.

El workflow `.github/workflows/play-bundle.yml` se ejecuta manualmente o al pushear la rama
`codex/google-play-preparation`. No crea un tag, una GitHub Release ni publica en Google Play.

```bash
gh workflow run play-bundle.yml --ref main
# Selecciona el run correcto en Actions o descarga usando su ID:
gh run download RUN_ID --name Rumb-play-aab --dir dist/play
gh run download RUN_ID --name Rumb-play-unit-tests --dir dist/play-tests
```

El disparo manual con `--ref main` solo funciona cuando el workflow ya está en `main`.
Para preparar y verificar el primer bundle sin modificar `main`, pushea la rama indicada y
usa el run que genera ese push. No hace falta subir `versionCode` si ese código todavía no
se ha usado en Play; mantén el contador común con GitHub y usa un código mayor en cada
versión nueva, incluidos los bundles posteriores subidos a Play.

CI instala el SDK declarado en `compileSdk`, ejecuta **todos los tests JVM de `play`**
(`:app:testPlayDebugUnitTest`), construye `:app:bundlePlayRelease`, verifica la firma y entrega
el artifact `Rumb-play-aab` con `Rumb-X.Y.Z-vcNNN.aab` y `SHA256SUMS`. Los informes de tests
se guardan en `Rumb-play-unit-tests`, incluso si el build falla.

### Firma y continuidad de las instalaciones

El AAB utiliza los secrets existentes `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y
`KEY_PASSWORD`. El workflow falla si falta alguno: un bundle firmado con la clave debug no
es válido para publicar. El keystore se borra del runner al terminar y nunca se adjunta
como artifact. En local, Gradle sigue usando la clave debug cuando no se proporciona el
keystore de release; un build local de `bundlePlayRelease` no sustituye el artifact de CI.

El certificado que firma el AAB es la **clave de subida**. Play App Signing firma las APK
que reciben los usuarios con la **clave de firma de la app**, que puede ser distinta.
Al configurar la primera publicación, si las instalaciones de GitHub deben actualizarse
desde Play sin desinstalar, hay que importar a Play la clave de firma existente mediante
su proceso seguro de PEPK. Elegir una nueva clave generada por Google rompe esa continuidad,
aunque el AAB se haya firmado con el keystore de CI. Si la app ya existe en Play, compara
su certificado de subida con el del AAB y conserva la configuración existente.
Fuente: [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en).

### Requisitos que deben comprobarse antes de enviar a revisión

- **API de destino**: desde el 31 de agosto de 2026, las nuevas apps y actualizaciones de
  móvil deben usar `targetSdk` **36 o superior**. Esta preparación usa 36.
  `minSdk = 26` mantiene la compatibilidad desde Android 8.0 y no debe confundirse con
  la API de destino requerida para publicar.
  Fuente: [requisitos oficiales de API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-EN).
- **Páginas de memoria de 16 KB**: Rumb incluye código nativo de MapLibre. Esta preparación
  usa AGP 8.10.1 y MapLibre 11.7.0; las bibliotecas `.so` también deben ser compatibles.
  Comprueba el AAB en Play y prueba las funciones de mapa en un dispositivo/emulador de
  16 KB; una compilación y tests JVM correctos no demuestran esta compatibilidad.
  Fuente: [guía oficial de 16 KB](https://developer.android.com/guide/practices/page-sizes).
- **Publicación**: subir el artifact no equivale a publicar. Completa la ficha, política de
  privacidad, seguridad de datos, clasificación, declaraciones de permisos y cualquier
  requisito de pruebas de la cuenta; revisa el resultado en Play Console y envía la release
  a revisión cuando esté lista.

## Si el build del tag falla

El tag ya está pusheado pero no hay release: arregla el problema en `main`, borra y
re-crea el tag sobre el commit arreglado y vuelve a pushearlo:

```bash
git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z
git tag -a vX.Y.Z -m "Rumb X.Y.Z" && git push origin vX.Y.Z
```

(Es el único caso en que re-escribir un tag es aceptable: nunca sobre un release ya
publicado con la APK descargable.)
