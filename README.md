# WgetNIO - Descargador Recursivo con Java NIO

Cliente HTTP/HTTPS tipo `wget` usando **Java NIO** (Non-Blocking I/O) y **SSL/TLS** para descarga recursiva de sitios web.

---

## ✨ Características

- ✅ Descarga recursiva con profundidad configurable
- ✅ Soporte HTTP y HTTPS (SSL/TLS)
- ✅ Non-Blocking I/O con `Selector` y `SocketChannel`
- ✅ Creación automática de subdirectorios
- ✅ Archivos binarios sin corrupción (imágenes, PDFs)
- ✅ Manejo de redirecciones 301/302
- ✅ Prevención de descargas duplicadas

---

## 🔧 Compilación y Ejecución

### Compilar
```bash
cd WgetNIO
javac -d target/classes src/main/java/irmanayeli/wgetnio/WgetNIO.java
```

### Ejecutar
```bash
java -cp target/classes irmanayeli.wgetnio.WgetNIO <URL> <PROFUNDIDAD>
```

**Ejemplo:**
```bash
java -cp target/classes irmanayeli.wgetnio.WgetNIO http://textfiles.com 2
```

---

## 🌐 Ejemplos de Uso

### 1. Motherfucking Website
```bash
java -cp target/classes irmanayeli.wgetnio.WgetNIO http://motherfuckingwebsite.com 2
```
**Resultado:** `0-Descargas/motherfuckingwebsite_com/index.html`

### 2. TextFiles (con imágenes)
```bash
java -cp target/classes irmanayeli.wgetnio.WgetNIO http://textfiles.com 2
```
**Resultado:**
````
/textfiles_com
    /imagen1.jpg
    /documento1.pdf
    - index.html
    - estilo.css
    - script.js
````

---

## 📁 Estructura de Directorios

El programa crea una estructura de directorios local que refleja la del servidor:

```
/descargas
    /example.com
        /pagina1
            - index.html
            - imagen1.png
        /pagina2
            - index.html
```

### Nombres de Archivos

- **HTML**: `index.html` por defecto
- **Otros**: Se conserva la extensión original (ej. `.jpg`, `.pdf`)

### Manejo de Archivos Duplicados

- **Prevención**: Se evita descargar URLs ya visitadas
- **Mismo contenido, diferente URL**: Se guarda el archivo, pero se evita procesar como nuevo

---

## 🔄 Recursividad

### Profundidad de Descarga

- **Límite**: `-l` o `--level` en la línea de comandos
- **Por defecto**: 5 niveles

### Comportamiento en Diferentes Niveles

- **Nivel 0**: Solo descarga la página principal
- **Nivel 1**: Descarga página principal + enlaces directos
- **Nivel 2+**: Descarga recursiva según enlaces encontrados

### Ejemplo de Recursividad

```java
// Supongamos una URL: http://example.com/nivel1
// Contenido:
<html>
  <body>
    <a href="/nivel2a">Nivel 2A</a>
    <a href="/nivel2b">Nivel 2B</a>
  </body>
</html>

// Proceso:
1. Descarga http://example.com/nivel1
2. Encuentra enlaces a /nivel2a y /nivel2b
3. Encola /nivel2a y /nivel2b con profundidad 2
4. Descarga nivel 2A y nivel 2B recursivamente
```