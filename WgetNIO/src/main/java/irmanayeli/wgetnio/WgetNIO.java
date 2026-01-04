package irmanayeli.wgetnio;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

public class WgetNIO {

    // Clase auxiliar para almacenar URL con su profundidad
    private static class UrlConProfundidad {
        String url;
        int profundidad;
        
        UrlConProfundidad(String url, int profundidad) {
            this.url = url;
            this.profundidad = profundidad;
        }
    }

    private static FileChannel archivoSalida;
    private static ByteArrayOutputStream bufferContenido;
    private static String directorioSalida;
    private static String hostActual;
    private static String rutaActual;
    private static String esquemaActual;
    private static int puertoActual;
    private static int profundidadActual;
    private static int profundidadMaxima;
    private static Set<String> descargadas = new HashSet<>();
    private static Queue<UrlConProfundidad> porDescargar = new LinkedList<>();
    private static boolean headerProcesado = false;
    private static int codigoHttpActual = 0;
    private static String redireccionPendiente = null;
    private static String contentType = null;
    private static boolean usarTLS = false;
    private static SSLEngine sslEngine;
    private static ByteBuffer netIn;
    private static ByteBuffer netOut;
    private static ByteBuffer appIn;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    public static void main(String[] args) {
        try {
            // Parsear argumentos
            String urlIngresada = (args.length > 0) ? args[0] : "default";
            profundidadMaxima = (args.length > 1) ? Integer.parseInt(args[1]) : 1;

            // Determinar URL
            String urlFinal;
            if ("default".equalsIgnoreCase(urlIngresada)) {
                urlFinal = "http://www.google.com/";
                System.out.println("[MODO DEFAULT]: Descargando www.google.com");
            } else {
                urlFinal = urlIngresada;
                System.out.println("[URL INGRESADA]: " + urlFinal);
            }

            // Parsear URL
            URL url = new URL(urlFinal);
            hostActual = url.getHost();
            if (hostActual == null || hostActual.isEmpty()) {
                hostActual = "www.google.com";
            }
            esquemaActual = url.getProtocol();
            usarTLS = esquemaActual.equalsIgnoreCase("https");
            puertoActual = url.getPort() == -1 ? (usarTLS ? 443 : 80) : url.getPort();
            rutaActual = url.getPath().isEmpty() ? "/" : url.getPath();
            if (url.getQuery() != null) {
                rutaActual += "?" + url.getQuery();
            }

            // Crear directorio principal con nombre del host dentro de 0-Descargas
            directorioSalida = "0-Descargas/" + hostActual.replace(".", "_");
            Files.createDirectories(Paths.get(directorioSalida));
            System.out.println("[DIRECTORIO]: Creado '" + directorioSalida + "'");
            System.out.println("[PROFUNDIDAD]: " + profundidadMaxima + " nivel(es)");
            System.out.println("[HOST]: " + hostActual + ":" + puertoActual);
            System.out.println("[PATH]: " + rutaActual);

            // Agregar URL inicial a la cola con profundidad 0
            porDescargar.add(new UrlConProfundidad(rutaActual, 0));
            descargadas.add(hostActual + rutaActual);

            // Iniciar descarga
            descargarRecursivo();

        } catch (Exception e) {
            System.err.println("Error al parsear URL o crear directorio:");
            e.printStackTrace();
        }
    }

    private static void descargarRecursivo() throws Exception {
        while (!porDescargar.isEmpty()) {
            UrlConProfundidad actual = porDescargar.poll();
            rutaActual = actual.url;
            profundidadActual = actual.profundidad;
            
            System.out.println("\n[DESCARGANDO] Nivel " + profundidadActual + ": " + rutaActual);
            System.out.println("[PROTOCOLO]: " + (usarTLS ? "HTTPS" : "HTTP") + " en puerto " + puertoActual);

            // Resetear buffers TLS si cambiamos de protocolo
            if (usarTLS) {
                netIn = null;
                netOut = null;
                appIn = null;
                sslEngine = null;
            }

            // 1. EL "TIMBRE": El Selector nos avisará cuando algo pase
            Selector selector = Selector.open();

            // 2. EL "CANAL": Abrimos la conexión hacia el servidor
            SocketChannel canal = SocketChannel.open();
            canal.configureBlocking(false); // <--- Esto lo hace No Bloqueante

            // Intentamos conectar al host dinámico
            canal.connect(new InetSocketAddress(hostActual, puertoActual));

            // 3. EL REGISTRO: Le decimos al selector que vigile cuando el canal se CONECTE
            canal.register(selector, SelectionKey.OP_CONNECT);

            System.out.println("Iniciando Wget No Bloqueante...");

            boolean completado = false;
            while (!completado) {
                // El programa se queda aquí esperando a que "suene el timbre"
                selector.select();

                // Obtenemos las "llaves" de los eventos que ocurrieron
                Set<SelectionKey> llaves = selector.selectedKeys();
                Iterator<SelectionKey> iterador = llaves.iterator();

                while (iterador.hasNext()) {
                    SelectionKey llave = iterador.next();
                    iterador.remove(); // Quitamos la llave para no procesarla dos veces

                    if (llave.isConnectable()) {
                        gestionarConexion(llave, selector);
                    } else if (llave.isWritable()) {
                        gestionarEscritura(llave, selector);
                    } else if (llave.isReadable()) {
                        completado = gestionarLectura(llave);
                    }
                }
            }

            selector.close();
            
            // Resetear variables para la siguiente descarga
            headerProcesado = false;
            codigoHttpActual = 0;
            redireccionPendiente = null;
            contentType = null;
            bufferContenido = null;
        }

        System.out.println("\n[COMPLETADO]: Descarga recursiva finalizada.");
    }

    private static void gestionarConexion(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // Terminamos el proceso de conexión
        if (sc.finishConnect()) {
            System.out.println("1. [CONECTADO]: Ya podemos hablar con " + hostActual + ":" + puertoActual);
            if (usarTLS) {
                iniciarTLS(sc);
                realizarHandshake(sc);
            }
            // Ahora le decimos que queremos ESCRIBIR osea mandar el GET
            sc.register(selector, SelectionKey.OP_WRITE);
        }
    }

    private static void gestionarEscritura(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // La petición HTTP con host dinámico y User-Agent
        String peticion = "GET " + rutaActual + " HTTP/1.1\r\n" +
                         "Host: " + hostActual + "\r\n" +
                         "User-Agent: " + USER_AGENT + "\r\n" +
                         "Connection: close\r\n\r\n";
        
        ByteBuffer buffer = ByteBuffer.wrap(peticion.getBytes());
        if (usarTLS) {
            escribirTLS(sc, buffer);
        } else {
            sc.write(buffer);
        }
        System.out.println("2. [PETICIÓN ENVIADA]: GET " + rutaActual);
        
        // Ya pedimos ahora queremos LEER lo que nos manden
        sc.register(selector, SelectionKey.OP_READ);
    }

    private static boolean gestionarLectura(SelectionKey llave) {
        SocketChannel sc = (SocketChannel) llave.channel();
        if (usarTLS) {
            return gestionarLecturaTLS(llave, sc);
        }

        ByteBuffer buffer = ByteBuffer.allocate(4096);

        try {
            prepararArchivoSalida();

            int bytesLeidos = sc.read(buffer);

            // -1 indica que el servidor terminó de enviar datos
            if (bytesLeidos == -1) {
                // Procesar headers si no se han procesado aún
                if (!headerProcesado && bufferContenido != null) {
                    procesarHeaders();
                }
                
                // Guardar solo el body (sin headers) al archivo final
                guardarBodySinHeaders();
                
                // Mostrar tipo de archivo descargado
                String tipoArchivo = determinarTipoArchivo();
                System.out.println("3. [FIN " + codigoHttpActual + "]: Descarga de " + rutaActual + " completa (" + tipoArchivo + ")");

                // Manejar redirección 301/302 (solo HTTP mismo host)
                manejarRedireccion();

                // Parsear HTML y encolar nuevos enlaces SOLO si es HTML y no hemos llegado al máximo
                if (profundidadActual + 1 < profundidadMaxima && 
                    bufferContenido != null && 
                    codigoHttpActual == 200 && 
                    esContenidoHTML()) {
                    
                    String contenido = bufferContenido.toString("UTF-8");
                    // Separar body de headers
                    int finHeader = contenido.indexOf("\r\n\r\n");
                    if (finHeader == -1) {
                        finHeader = contenido.indexOf("\n\n");
                    }
                    String html = (finHeader != -1) ? contenido.substring(finHeader + 4) : contenido;
                    encolarEnlaces(html);
                } else if (codigoHttpActual == 200 && !esContenidoHTML()) {
                    System.out.println("   [INFO]: Recurso no-HTML descargado, no se buscan enlaces");
                }
                
                cerrarRecursos(llave, sc);
                return true;
            }

            int bytesAcumulados = 0;
            while (bytesLeidos > 0) {
                buffer.flip();
                // NO escribir directamente al archivo, solo al buffer de contenido
                bufferContenido.write(buffer.array(), 0, buffer.limit());
                bytesAcumulados += buffer.limit();
                buffer.clear();
                bytesLeidos = sc.read(buffer);

                // Procesar headers si aún no se han procesado
                if (!headerProcesado && bufferContenido != null) {
                    procesarHeaders();
                }
            }

            if (bytesAcumulados > 0) {
                System.out.println("3. [LEYENDO]: " + bytesAcumulados + " bytes almacenados");
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            cerrarRecursos(llave, sc);
            return true;
        }
    }

    private static void procesarHeaders() {
        if (headerProcesado || bufferContenido == null) return;

        try {
            String contenido = bufferContenido.toString("UTF-8");
            int finHeader = contenido.indexOf("\r\n\r\n");
            if (finHeader == -1) {
                finHeader = contenido.indexOf("\n\n");
                if (finHeader == -1) return;
            }

            String headers = contenido.substring(0, finHeader);
            String[] lineas = headers.split("\r\n|\n");

            if (lineas.length > 0) {
                String statusLine = lineas[0];
                // Parsear status line: "HTTP/1.1 200 OK"
                String[] partes = statusLine.split(" ");
                if (partes.length >= 2) {
                    try {
                        codigoHttpActual = Integer.parseInt(partes[1]);
                    } catch (NumberFormatException e) {
                        codigoHttpActual = 999;
                    }
                }
            }

            // Buscar Location para redirecciones y Content-Type
            for (String linea : lineas) {
                String lineaLower = linea.toLowerCase();
                if (lineaLower.startsWith("location:")) {
                    redireccionPendiente = linea.substring("location:".length()).trim();
                } else if (lineaLower.startsWith("content-type:")) {
                    contentType = linea.substring("content-type:".length()).trim();
                    System.out.println("   [CONTENT-TYPE]: " + contentType);
                }
            }

            headerProcesado = true;
        } catch (Exception e) {
            // Ignorar si no se puede parsear todavía
        }
    }

    private static void manejarRedireccion() {
        // Manejar redirección 301/302
        if ((codigoHttpActual == 301 || codigoHttpActual == 302) && redireccionPendiente != null) {
            try {
                URL base = new URL(esquemaActual, hostActual, puertoActual, rutaActual);
                URL destino = new URL(base, redireccionPendiente);

                String nuevoHost = destino.getHost();
                String nuevoEsquema = destino.getProtocol();
                int nuevoPuerto = destino.getPort() == -1 ? (nuevoEsquema.equalsIgnoreCase("https") ? 443 : 80) : destino.getPort();

                // Solo permitir redirecciones al mismo host
                if (!nuevoHost.equalsIgnoreCase(hostActual)) {
                    System.out.println("   [REDIR]: Se omite redirección a host distinto: " + destino);
                    return;
                }

                String nuevoPath = destino.getPath().isEmpty() ? "/" : destino.getPath();
                if (destino.getQuery() != null) {
                    nuevoPath += "?" + destino.getQuery();
                }

                // Actualizar las variables globales para la redirección
                esquemaActual = nuevoEsquema;
                puertoActual = nuevoPuerto;
                usarTLS = nuevoEsquema.equalsIgnoreCase("https");

                String clave = nuevoHost + nuevoPath;
                if (!descargadas.contains(clave)) {
                    descargadas.add(clave);
                    porDescargar.add(new UrlConProfundidad(nuevoPath, profundidadActual));
                    System.out.println("   [REDIR ENCOLADO]: " + nuevoEsquema + "://" + nuevoHost + ":" + nuevoPuerto + nuevoPath);
                }
            } catch (Exception e) {
                System.out.println("   [REDIR]: Error al procesar Location: " + e.getMessage());
            }
        }
    }

    private static void encolarEnlaces(String html) {
        try {
            // Buscamos href/src en comillas dobles Y simples
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)(?:href|src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            java.util.regex.Matcher m = p.matcher(html);

            URL base = new URL(esquemaActual, hostActual, puertoActual, rutaActual);
            int enlacesEncontrados = 0;
            int enlacesTotales = 0;

            while (m.find()) {
                String enlace = m.group(1).trim();
                enlacesTotales++;
                
                if (enlace.isEmpty()) continue;
                if (enlace.startsWith("#") || enlace.startsWith("javascript:") || enlace.startsWith("data:")) {
                    System.out.println("   [IGNORADO - fragmento/script]: " + enlace);
                    continue;
                }

                try {
                    URL resuelta = new URL(base, enlace);

                    // Solo mismo host
                    if (!resuelta.getHost().equalsIgnoreCase(hostActual)) {
                        System.out.println("   [IGNORADO - host externo]: " + enlace + " -> " + resuelta.getHost());
                        continue;
                    }

                    String path = resuelta.getPath().isEmpty() ? "/" : resuelta.getPath();
                    if (resuelta.getQuery() != null) {
                        path += "?" + resuelta.getQuery();
                    }

                    // Filtrar: solo permitir ciertos tipos de archivo
                    if (!esArchivoPermitido(path)) {
                        System.out.println("   [IGNORADO - extensión no permitida]: " + path);
                        continue;
                    }

                    String clave = hostActual + path;
                    if (!descargadas.contains(clave)) {
                        descargadas.add(clave);
                        porDescargar.add(new UrlConProfundidad(path, profundidadActual + 1));
                        enlacesEncontrados++;
                        System.out.println("   [ENCOLADO]: " + path + " (profundidad " + (profundidadActual + 1) + ")");
                    } else {
                        System.out.println("   [IGNORADO - ya descargado]: " + path);
                    }
                } catch (Exception e) {
                    System.out.println("   [ERROR al parsear]: " + enlace + " - " + e.getMessage());
                }
            }

            System.out.println("   [RESUMEN]: " + enlacesTotales + " enlaces encontrados, " + enlacesEncontrados + " encolados");
            
            if (enlacesEncontrados == 0 && enlacesTotales == 0) {
                System.out.println("   [AVISO]: No se encontraron enlaces en el HTML");
            }
        } catch (Exception e) {
            System.err.println("[WARN] No se pudieron parsear enlaces: " + e.getMessage());
        }
    }

    private static boolean esArchivoPermitido(String ruta) {
        // Si la ruta no tiene extensión, asumimos que es HTML
        if (!ruta.contains(".") || ruta.endsWith("/")) {
            return true;
        }
        
        String[] extensionesPermitidas = {
            ".html", ".htm", ".txt", ".pdf", ".zip", ".jar", ".java", 
            ".png", ".jpg", ".jpeg", ".gif", ".css", ".js", ".json", ".xml"
        };

        for (String ext : extensionesPermitidas) {
            if (ruta.toLowerCase().endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    // --- HTTPS (TLS) helpers ---
    private static void iniciarTLS(SocketChannel sc) throws Exception {
        SSLContext ctx = SSLContext.getDefault();
        sslEngine = ctx.createSSLEngine(hostActual, puertoActual);
        sslEngine.setUseClientMode(true);
        int appSize = sslEngine.getSession().getApplicationBufferSize();
        int netSize = sslEngine.getSession().getPacketBufferSize();
        netIn = ByteBuffer.allocate(netSize);
        netOut = ByteBuffer.allocate(netSize);
        appIn = ByteBuffer.allocate(appSize * 2);
    }

    private static void realizarHandshake(SocketChannel sc) throws Exception {
        sslEngine.beginHandshake();
        SSLEngineResult.HandshakeStatus hs = sslEngine.getHandshakeStatus();

        ByteBuffer dummy = ByteBuffer.allocate(0);

        while (true) {
            switch (hs) {
                case NEED_WRAP:
                    netOut.clear();
                    SSLEngineResult rWrap = sslEngine.wrap(dummy, netOut);
                    hs = rWrap.getHandshakeStatus();
                    if (rWrap.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = enlarge(netOut, sslEngine.getSession().getPacketBufferSize());
                        continue;
                    }
                    netOut.flip();
                    while (netOut.hasRemaining()) {
                        sc.write(netOut);
                    }
                    break;
                case NEED_UNWRAP:
                    if (sc.read(netIn) < 0) {
                        throw new SSLException("Canal cerrado durante handshake");
                    }
                    netIn.flip();
                    SSLEngineResult rUnwrap = sslEngine.unwrap(netIn, appIn);
                    netIn.compact();
                    hs = rUnwrap.getHandshakeStatus();
                    if (rUnwrap.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // necesitamos más datos
                    } else if (rUnwrap.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        appIn = enlarge(appIn, sslEngine.getSession().getApplicationBufferSize());
                    }
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    hs = sslEngine.getHandshakeStatus();
                    break;
                case FINISHED:
                    return;
                case NOT_HANDSHAKING:
                    return;
                default:
                    break;
            }
        }
    }

    private static ByteBuffer enlarge(ByteBuffer original, int newCapacity) {
        if (newCapacity <= original.capacity()) {
            newCapacity = original.capacity() * 2;
        }
        ByteBuffer nuevo = ByteBuffer.allocate(newCapacity);
        original.flip();
        nuevo.put(original);
        return nuevo;
    }

    private static void escribirTLS(SocketChannel sc, ByteBuffer appData) throws Exception {
        while (appData.hasRemaining()) {
            netOut.clear();
            SSLEngineResult r = sslEngine.wrap(appData, netOut);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = enlarge(netOut, sslEngine.getSession().getPacketBufferSize());
                continue;
            }
            netOut.flip();
            while (netOut.hasRemaining()) {
                sc.write(netOut);
            }
        }
    }

    private static boolean gestionarLecturaTLS(SelectionKey llave, SocketChannel sc) {
        try {
            prepararArchivoSalida();

            int leido = sc.read(netIn);
            if (leido == -1) {
                if (!headerProcesado && bufferContenido != null) {
                    procesarHeaders();
                }
                
                // Guardar solo el body (sin headers) al archivo final
                guardarBodySinHeaders();
                
                String tipoArchivo = determinarTipoArchivo();
                System.out.println("3. [FIN " + codigoHttpActual + "]: Descarga de " + rutaActual + " completa (" + tipoArchivo + " - TLS)");
                
                // Parsear HTML y encolar nuevos enlaces SOLO si es HTML
                if (profundidadActual + 1 < profundidadMaxima && 
                    bufferContenido != null && 
                    codigoHttpActual == 200 && 
                    esContenidoHTML()) {
                    
                    String contenido = bufferContenido.toString("UTF-8");
                    int finHeader = contenido.indexOf("\r\n\r\n");
                    if (finHeader == -1) {
                        finHeader = contenido.indexOf("\n\n");
                    }
                    String html = (finHeader != -1) ? contenido.substring(finHeader + 4) : contenido;
                    encolarEnlaces(html);
                } else if (codigoHttpActual == 200 && !esContenidoHTML()) {
                    System.out.println("   [INFO]: Recurso no-HTML descargado, no se buscan enlaces");
                }
                
                manejarRedireccion();
                cerrarRecursos(llave, sc);
                return true;
            }

            netIn.flip();
            while (true) {
                SSLEngineResult r = sslEngine.unwrap(netIn, appIn);
                switch (r.getStatus()) {
                    case OK:
                        procesarAppBuffer();
                        break;
                    case BUFFER_UNDERFLOW:
                        netIn.compact();
                        return false;
                    case BUFFER_OVERFLOW:
                        appIn = enlarge(appIn, sslEngine.getSession().getApplicationBufferSize());
                        break;
                    case CLOSED:
                        netIn.compact();
                        cerrarRecursos(llave, sc);
                        return true;
                }
                if (netIn.remaining() == 0) {
                    netIn.compact();
                    break;
                }
            }

            return false;
        } catch (Exception e) {
            e.printStackTrace();
            cerrarRecursos(llave, sc);
            return true;
        }
    }

    private static void procesarAppBuffer() throws Exception {
        appIn.flip();
        if (appIn.remaining() > 0) {
            byte[] datos = new byte[appIn.remaining()];
            appIn.get(datos);
            // Solo guardar en buffer de contenido, no directamente al archivo
            bufferContenido.write(datos);
            if (!headerProcesado && bufferContenido != null) {
                procesarHeaders();
            }
            System.out.println("3. [LEYENDO]: " + datos.length + " bytes almacenados (TLS)");
        }
        appIn.clear();
    }

    private static void guardarBodySinHeaders() throws Exception {
        if (bufferContenido == null || archivoSalida == null) {
            return;
        }

        byte[] contenidoCompleto = bufferContenido.toByteArray();
        
        // Buscar el final de los headers HTTP
        int finHeaders = -1;
        for (int i = 0; i < contenidoCompleto.length - 3; i++) {
            // Buscar \r\n\r\n
            if (contenidoCompleto[i] == '\r' && contenidoCompleto[i+1] == '\n' &&
                contenidoCompleto[i+2] == '\r' && contenidoCompleto[i+3] == '\n') {
                finHeaders = i + 4;
                break;
            }
        }
        
        // Si no encontramos \r\n\r\n, buscar \n\n
        if (finHeaders == -1) {
            for (int i = 0; i < contenidoCompleto.length - 1; i++) {
                if (contenidoCompleto[i] == '\n' && contenidoCompleto[i+1] == '\n') {
                    finHeaders = i + 2;
                    break;
                }
            }
        }

        // Escribir solo el body al archivo
        if (finHeaders != -1 && finHeaders < contenidoCompleto.length) {
            int longitudBody = contenidoCompleto.length - finHeaders;
            ByteBuffer bodyBuffer = ByteBuffer.wrap(contenidoCompleto, finHeaders, longitudBody);
            archivoSalida.write(bodyBuffer);
            System.out.println("   [GUARDADO]: " + longitudBody + " bytes de contenido (sin headers HTTP)");
        } else {
            // Si no hay headers (raro), guardar todo
            ByteBuffer todoBuffer = ByteBuffer.wrap(contenidoCompleto);
            archivoSalida.write(todoBuffer);
            System.out.println("   [GUARDADO]: " + contenidoCompleto.length + " bytes (sin headers detectados)");
        }
    }

    private static void prepararArchivoSalida() throws Exception {
        if (archivoSalida != null && archivoSalida.isOpen()) {
            return; // Ya está preparado
        }

        if (bufferContenido == null) {
            bufferContenido = new ByteArrayOutputStream();
        }

        // Crear estructura de directorios basada en la ruta
        String rutaLimpia = rutaActual;
        
        // Remover query string para el nombre de archivo
        int queryIndex = rutaLimpia.indexOf('?');
        if (queryIndex != -1) {
            rutaLimpia = rutaLimpia.substring(0, queryIndex);
        }

        // Determinar nombre de archivo y directorios
        String nombreArchivo;
        String subdirectorios = "";

        if (rutaLimpia.equals("/") || rutaLimpia.isEmpty()) {
            nombreArchivo = "index.html";
        } else if (rutaLimpia.endsWith("/")) {
            // Es un directorio: /docs/ -> docs/index.html
            subdirectorios = rutaLimpia.substring(1); // Quitar '/' inicial
            nombreArchivo = "index.html";
        } else {
            // Es un archivo: /images/logo.png o /about
            int lastSlash = rutaLimpia.lastIndexOf('/');
            if (lastSlash > 0) {
                subdirectorios = rutaLimpia.substring(1, lastSlash + 1); // /images/ -> images/
                nombreArchivo = rutaLimpia.substring(lastSlash + 1);
            } else {
                nombreArchivo = rutaLimpia.substring(1); // Quitar '/' inicial
            }
            
            // Si no tiene extensión, agregar .html
            if (!nombreArchivo.contains(".")) {
                nombreArchivo += ".html";
            }
        }

        // Sanitizar nombre de archivo (quitar caracteres no válidos)
        nombreArchivo = nombreArchivo.replaceAll("[<>:\"|?*]", "_");

        // Crear ruta completa con subdirectorios
        String rutaCompleta;
        if (!subdirectorios.isEmpty()) {
            String directorioCompleto = directorioSalida + "/" + subdirectorios;
            Files.createDirectories(Paths.get(directorioCompleto));
            rutaCompleta = directorioCompleto + nombreArchivo;
            System.out.println("[SUBDIRECTORIO]: Creado '" + directorioCompleto + "'");
        } else {
            rutaCompleta = directorioSalida + "/" + nombreArchivo;
        }

        archivoSalida = new FileOutputStream(rutaCompleta).getChannel();
        System.out.println("[ARCHIVO]: Preparado para escribir en '" + rutaCompleta + "'");
    }

    private static void cerrarRecursos(SelectionKey llave, SocketChannel sc) {
        try {
            llave.cancel();
        } catch (Exception ignored) {
        }

        try {
            sc.close();
        } catch (Exception ignored) {
        }

        try {
            if (archivoSalida != null && archivoSalida.isOpen()) {
                archivoSalida.close();
                archivoSalida = null; // Resetear para próxima descarga
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean esContenidoHTML() {
        // Verificar por Content-Type
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            return ct.contains("text/html") || ct.contains("application/xhtml");
        }
        
        // Si no hay Content-Type, verificar por extensión de archivo
        String rutaLower = rutaActual.toLowerCase();
        return rutaLower.endsWith(".html") || 
               rutaLower.endsWith(".htm") || 
               rutaLower.endsWith("/") || 
               !rutaLower.contains(".");
    }

    private static String determinarTipoArchivo() {
        if (contentType != null) {
            if (contentType.toLowerCase().contains("text/html")) return "HTML";
            if (contentType.toLowerCase().contains("image/")) return "IMAGEN";
            if (contentType.toLowerCase().contains("text/css")) return "CSS";
            if (contentType.toLowerCase().contains("javascript")) return "JS";
            if (contentType.toLowerCase().contains("application/json")) return "JSON";
            if (contentType.toLowerCase().contains("text/plain")) return "TXT";
            return contentType.split(";")[0]; // Retornar solo el tipo sin charset
        }
        
        // Fallback: determinar por extensión
        String rutaLower = rutaActual.toLowerCase();
        if (rutaLower.endsWith(".html") || rutaLower.endsWith(".htm")) return "HTML";
        if (rutaLower.endsWith(".png") || rutaLower.endsWith(".jpg") || rutaLower.endsWith(".jpeg") || rutaLower.endsWith(".gif")) return "IMAGEN";
        if (rutaLower.endsWith(".css")) return "CSS";
        if (rutaLower.endsWith(".js")) return "JS";
        if (rutaLower.endsWith(".txt")) return "TXT";
        
        return "DESCONOCIDO";
    }
}
