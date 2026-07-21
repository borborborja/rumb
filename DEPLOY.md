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

1. **Verificar** (CI no ejecuta tests):
   ```bash
   ./gradlew :app:testDebugUnitTest
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
5. CI (`.github/workflows/release.yml`) hace el resto: compila `assembleRelease` firmada,
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

## Si el build del tag falla

El tag ya está pusheado pero no hay release: arregla el problema en `main`, borra y
re-crea el tag sobre el commit arreglado y vuelve a pushearlo:

```bash
git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z
git tag -a vX.Y.Z -m "Rumb X.Y.Z" && git push origin vX.Y.Z
```

(Es el único caso en que re-escribir un tag es aceptable: nunca sobre un release ya
publicado con la APK descargable.)
