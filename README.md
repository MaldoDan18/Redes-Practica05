# Redes-Practica05

## WgetNIO: cómo funciona

Este ejemplo implementa un descargador HTTP no bloqueante usando Java NIO. El flujo es:

1) Arranque: se abre un `Selector` y un `SocketChannel` en modo no bloqueante hacia el host (en el ejemplo `www.google.com:80`). Se registra el interés en `OP_CONNECT`.
2) Conexión: cuando el selector indica `isConnectable`, se completa el handshake con `finishConnect()` y se cambia el interés a `OP_WRITE`.
3) Petición: en `isWritable` se envía un GET básico (`GET / HTTP/1.1`, `Host`, `Connection: close`) y se cambia el interés a `OP_READ`.
4) Lectura: en `isReadable` se leen bytes en un `ByteBuffer` y se escriben a `index.html` hasta EOF (`read` devuelve -1). Luego se cierran canal, selector y archivo.

## Qué falta para parecerse a wget recursivo

- Entrada dinámica: parsear argumentos CLI y URLs (host, puerto, ruta) en vez de valores fijos.
- HTTP completo: manejar redirecciones 3xx, `Content-Length`, `Transfer-Encoding: chunked`, timeouts y cabeceras opcionales (User-Agent, gzip, Range para reanudar).
- Rutas locales: crear un directorio con el host y replicar la estructura de paths remotos al guardar cada recurso.
- Recursión: tras descargar HTML, parsear enlaces (`href`/`src`), normalizar URLs relativas/absolutas, filtrar por dominio/profundidad y usar una cola (BFS/DFS) con un set de visitados para evitar duplicados.
- Respeto a robots.txt y exclusiones opcionales.
- Concurrencia: múltiples descargas en paralelo (varios canales) y control de errores/reintentos.

Con estos pasos, el esqueleto actual NIO puede evolucionar hacia un wget que descarga sitios por directorios.