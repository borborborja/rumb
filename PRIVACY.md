# Política de privacidad de Rumb

**Desarrollador:** micapum
**Aplicación:** Rumb (`cat.rumb.app`), distribución Google Play
**Actualización:** 9 de octubre de 2026
**Contacto de privacidad y soporte:** [rumb@micapum.net](mailto:rumb@micapum.net)

Rumb permite registrar actividades deportivas, gestionar rutas, consultar mapas y utilizar sensores Bluetooth compatibles. Esta política explica qué datos utiliza la aplicación y cuándo pueden salir del teléfono.

## Datos utilizados en el teléfono

Al iniciar una grabación, Rumb utiliza la ubicación del teléfono para registrar coordenadas, altitud, velocidad, precisión y hora. La grabación puede continuar con la pantalla apagada mediante un servicio con notificación visible, hasta que detengas la actividad.

Si conectas sensores compatibles, puede registrar frecuencia cardíaca, cadencia y potencia. Si utilizas una báscula compatible, puede guardar peso e impedancia y calcular estimaciones de composición corporal. El perfil que indiques puede incluir peso, altura, edad, sexo y frecuencia cardíaca máxima. Las lecturas de invitado de la báscula se muestran sin guardar un historial.

Rumb guarda actividades, rutas, nombres, fechas, vueltas, estadísticas, preferencias y referencias a los sensores vinculados. También puede leer y exportar los archivos de actividad que selecciones. El perfil y el historial de pesajes se guardan en el teléfono; el perfil puede utilizarse para calcular estimaciones de calorías incluidas en las estadísticas y archivos exportados.

Rumb no exige crear una cuenta propia. Las cuentas que utilices para Endurain o WebDAV corresponden al servicio que configures.

## Compras y suscripciones

La suscripción opcional Rumb Premium se contrata mediante el sistema de facturación de Google Play. Google gestiona el pago, la renovación y la cancelación conforme a sus condiciones y su [política de privacidad](https://policies.google.com/privacy). Rumb no solicita ni recibe el número completo de tu tarjeta ni su código de seguridad.

Rumb consulta a Google Play los planes disponibles y las compras de suscripción para comprobar o restaurar el acceso a las funciones Premium. Procesa los identificadores del producto, el precio que facilita la tienda, el estado de la compra y de su confirmación, la información firmada de la compra y su token. La firma de compra se comprueba en el dispositivo. El token se utiliza para confirmar la entrega de la suscripción ante Google Play.

La aplicación mantiene la información necesaria de la compra y el estado de acceso asociado a compras en la memoria del proceso mientras está en uso. Esta integración no guarda recibos, firmas ni tokens de compra en las preferencias o en la base de datos de Rumb ni los envía a un servidor propio del desarrollador. Al iniciar una grabación, Rumb guarda temporalmente en las preferencias si esa sesión tiene habilitadas las funciones Premium, para poder recuperarla si se cierra el proceso. Esa configuración de la sesión se elimina cuando termina la grabación y no constituye un recibo ni desbloquea nuevas sesiones. Google Play conserva sus propios registros de transacciones conforme a sus condiciones y políticas. La activación y restauración de Premium pueden necesitar conexión a Internet.

Rumb puede facilitar un código gratuito de demostración para revisar las mismas funciones avanzadas. La pantalla identifica este acceso por separado: no es una compra ni una suscripción y no genera cargos ni renovaciones. El código se valida en el dispositivo y se conserva en un archivo privado excluido de las copias de seguridad de Android. Puedes retirar el acceso desde la pantalla Premium; al hacerlo se elimina ese archivo. El código no se envía a un servidor propio del desarrollador.

Puedes gestionar o cancelar la suscripción en Google Play. Borrar los datos de Rumb o desinstalarla no cancela la suscripción ni elimina los registros de compra que conserve Google. La grabación GPS, los mapas básicos en línea y la consulta y exportación de actividades guardadas no requieren suscripción.

## Mapas y cálculo de rutas

Los mapas en línea solicitan imágenes al proveedor de la capa seleccionada, como OpenStreetMap, ICGC, IGN, Esri, OpenTopoMap, CyclOSM o Tracestrack. El proveedor recibe las solicitudes de las zonas consultadas y la información técnica necesaria para responder, como la dirección IP. Las capas que requieren una clave utilizan la que configures.

La búsqueda de municipios con Nominatim de OpenStreetMap está desactivada por defecto. Si activas «Buscar municipios en línea» en los ajustes de mapa, autorizas a Rumb a enviar las coordenadas iniciales de tus rutas y actividades guardadas para obtener los nombres de los municipios. Tras activarla, puede consultar rutas anteriores pendientes y nuevas actividades guardadas o importadas. La respuesta queda almacenada junto a la actividad. Puedes desactivar la opción para impedir nuevas consultas; esto no retira las solicitudes ya enviadas.

Cuando solicitas el cálculo de una ruta con BRouter web, Rumb envía los puntos indicados y el perfil de ruta al servidor utilizado, por defecto `brouter.de`. Si utilizas una instalación compatible de BRouter en el dispositivo, el cálculo se realiza mediante esa instalación.

Los proveedores de estos servicios gestionan sus propias solicitudes y registros conforme a sus políticas.

## Sincronización y archivos compartidos

Si configuras Endurain, WebDAV o una carpeta de exportación, Rumb puede enviar o guardar actividades en esos destinos. Al finalizar una grabación, puede sincronizarse automáticamente con los destinos configurados. También puedes iniciar envíos manuales y reintentos.

Los archivos pueden contener nombre y tipo de actividad, coordenadas, fechas y horas, vueltas, frecuencia cardíaca, cadencia, potencia y estimaciones de calorías, según los datos disponibles y el formato. Para autenticarse en el servicio elegido, Rumb utiliza las credenciales que introduzcas, como usuario, contraseña, clave de API o tokens de sesión.

Al compartir o exportar un archivo mediante otra aplicación, esa aplicación y el destinatario reciben el contenido seleccionado. Los servidores y aplicaciones elegidos gestionan las copias que reciben conforme a su configuración y políticas.

## Permisos y seguridad

Rumb solicita permisos de ubicación para mostrar tu posición y registrar actividades, de Bluetooth para buscar y conectar sensores y de notificaciones para mostrar grabaciones y descargas en curso. Puedes administrar los permisos en los ajustes de Android. Algunas funciones dejan de estar disponibles si retiras el permiso correspondiente.

Los servicios públicos de mapas, Nominatim y BRouter utilizados por defecto se consultan mediante HTTPS. La distribución de Google Play configura Android para rechazar conexiones HTTP sin cifrar; utiliza direcciones HTTPS para los destinos que configures y conserva tus credenciales de forma segura. Los datos locales se guardan en el almacenamiento de la aplicación, protegido por los controles de acceso de Android.

Esta distribución de Rumb no incorpora publicidad ni herramientas de analítica o envío automático de errores al desarrollador. La pantalla de depuración permite consultar registros técnicos locales. Si decides copiarlos, guardarlos o compartirlos, pueden incluir datos del dispositivo, errores y operaciones de la aplicación.

## Conservación y eliminación

Las rutas, actividades, mediciones y preferencias permanecen en el teléfono hasta que las elimines o borres los datos de Rumb desde Android. Puedes borrar actividades y mediciones de peso desde la aplicación. Puedes retirar permisos, desactivar la búsqueda de municipios y quitar los destinos configurados para limitar usos posteriores.

Android puede incluir datos de la aplicación en una copia de seguridad, según la configuración del dispositivo y del sistema. Utiliza los ajustes de Android y del proveedor de la copia para gestionarla.

El borrado local no elimina automáticamente los archivos exportados, las actividades de Endurain, los archivos de WebDAV o carpetas externas, ni los registros de proveedores de mapas y rutas. Gestiona esas copias mediante las funciones del servicio o contactando con su responsable.

Para consultas sobre privacidad de Rumb, utiliza el contacto indicado al principio. No envíes contraseñas ni claves de acceso al solicitar ayuda.

## Información de salud

Rumb ofrece funciones de actividad física y estimaciones de composición corporal. No es un dispositivo médico y no está destinada a diagnosticar, tratar, curar ni prevenir enfermedades. Consulta a un profesional sanitario para obtener asesoramiento, diagnóstico o tratamiento médico. Las estimaciones no sustituyen una evaluación profesional.

## Cambios en esta política

Esta política se actualizará cuando cambien las funciones o los datos tratados por Rumb. La versión vigente mostrará su fecha de actualización.
