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

public class WgetNIO {

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
    private static Queue<String> porDescargar = new LinkedList<>();
    private static boolean headerProcesado = false;
    private static int codigoHttpActual = 0;
    private static String redireccionPendiente = null;

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
            puertoActual = url.getPort() == -1 ? (esquemaActual.equals("https") ? 443 : 80) : url.getPort();
            rutaActual = url.getPath().isEmpty() ? "/" : url.getPath();
            if (url.getQuery() != null) {
                rutaActual += "?" + url.getQuery();
            }
            profundidadActual = 0;

            // Crear directorio principal con nombre del host dentro de 0-Descargas
            directorioSalida = "0-Descargas/" + hostActual.replace(".", "_");
            Files.createDirectories(Paths.get(directorioSalida));
            System.out.println("[DIRECTORIO]: Creado '" + directorioSalida + "'");
            System.out.println("[PROFUNDIDAD]: " + profundidadMaxima + " nivel(es)");
            System.out.println("[HOST]: " + hostActual + ":" + puertoActual);
            System.out.println("[PATH]: " + rutaActual);

            // Agregar URL inicial a la cola
            porDescargar.add(rutaActual);
            descargadas.add(hostActual + rutaActual);

            // Iniciar descarga
            descargarRecursivo();

        } catch (Exception e) {
            System.err.println("Error al parsear URL o crear directorio:");
            e.printStackTrace();
        }
    }

    private static void descargarRecursivo() throws Exception {
        if (profundidadActual >= profundidadMaxima) {
            System.out.println("[TERMINADO]: Profundidad máxima alcanzada.");
            return;
        }

        while (!porDescargar.isEmpty()) {
            rutaActual = porDescargar.poll();
            System.out.println("\n[DESCARGANDO] Nivel " + profundidadActual + ": " + rutaActual);

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
            profundidadActual++;

            if (profundidadActual >= profundidadMaxima) {
                break;
            }
        }

        System.out.println("\n[COMPLETADO]: Descarga recursiva finalizada.");
    }

    private static void gestionarConexion(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // Terminamos el proceso de conexión
        if (sc.finishConnect()) {
            System.out.println("1. [CONECTADO]: Ya podemos hablar con " + hostActual + ":" + puertoActual);
            // Ahora le decimos que queremos ESCRIBIR osea mandar el GET
            sc.register(selector, SelectionKey.OP_WRITE);
        }
    }

    private static void gestionarEscritura(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // La petición HTTP con host dinámico
        String peticion = "GET " + rutaActual + " HTTP/1.1\r\n" +
                         "Host: " + hostActual + "\r\n" +
                         "Connection: close\r\n\r\n";
        
        ByteBuffer buffer = ByteBuffer.wrap(peticion.getBytes());
        sc.write(buffer);
        System.out.println("2. [PETICIÓN ENVIADA]: GET " + rutaActual);
        
        // Ya pedimos ahora queremos LEER lo que nos manden
        sc.register(selector, SelectionKey.OP_READ);
    }

    private static boolean gestionarLectura(SelectionKey llave) {
        SocketChannel sc = (SocketChannel) llave.channel();
        ByteBuffer buffer = ByteBuffer.allocate(4096);

        try {
            if (archivoSalida == null) {
                // Crear buffer en memoria para luego parsear enlaces
                bufferContenido = new ByteArrayOutputStream();
                headerProcesado = false;
                codigoHttpActual = 0;
                redireccionPendiente = null;

                // Crear archivo replicando estructura de path
                String rutaLimpia = rutaActual;
                String queryParte = "";
                if (rutaLimpia.contains("?")) {
                    String[] split = rutaLimpia.split("\\?", 2);
                    rutaLimpia = split[0];
                    queryParte = "_" + split[1].replaceAll("[^a-zA-Z0-9._-]", "_");
                }

                if (rutaLimpia.endsWith("/")) {
                    rutaLimpia += "index.html";
                }
                if (rutaLimpia.isEmpty() || "/".equals(rutaLimpia)) {
                    rutaLimpia = "/index.html";
                }

                // Asegurar subdirectorios dentro del host
                String pathSinSlashInicial = rutaLimpia.startsWith("/") ? rutaLimpia.substring(1) : rutaLimpia;
                // Reemplazar "/" con separador del SO desde el inicio
                String pathConSeparador = pathSinSlashInicial.replace("/", java.io.File.separator);
                String rutaCompleta = directorioSalida + java.io.File.separator + pathConSeparador;

                // Si hay query, adjuntar al nombre
                int idx = rutaCompleta.lastIndexOf(java.io.File.separator);
                String directorioArchivo = rutaCompleta.substring(0, idx);
                String nombreArchivo = rutaCompleta.substring(idx + 1);
                if (!queryParte.isEmpty()) {
                    nombreArchivo = nombreArchivo + queryParte;
                }

                Files.createDirectories(Paths.get(directorioArchivo));
                String rutaFinal = directorioArchivo + java.io.File.separator + nombreArchivo;

                archivoSalida = new FileOutputStream(rutaFinal).getChannel();
                System.out.println("   [ARCHIVO]: " + rutaFinal);
            }

            int bytesLeidos = sc.read(buffer);

            // -1 indica que el servidor terminó de enviar datos
            if (bytesLeidos == -1) {
                // Procesar headers si no se han procesado aún
                if (!headerProcesado && bufferContenido != null) {
                    procesarHeaders();
                }
                System.out.println("3. [FIN " + codigoHttpActual + "]: Descarga de " + rutaActual + " completa.");

                // Manejar redirección 301/302 (solo HTTP mismo host)
                if ((codigoHttpActual == 301 || codigoHttpActual == 302) && redireccionPendiente != null) {
                    try {
                        URL base = new URL(esquemaActual, hostActual, puertoActual, rutaActual);
                        URL destino = new URL(base, redireccionPendiente);

                        if (destino.getProtocol().equalsIgnoreCase("https")) {
                            System.out.println("   [REDIR]: Se omite redirección a HTTPS (no soportado): " + destino);
                        } else {
                            String nuevoHost = destino.getHost();
                            if (!nuevoHost.equalsIgnoreCase(hostActual)) {
                                System.out.println("   [REDIR]: Se omite redirección a host distinto: " + destino);
                            } else {
                                String nuevoPath = destino.getPath().isEmpty() ? "/" : destino.getPath();
                                if (destino.getQuery() != null) {
                                    nuevoPath += "?" + destino.getQuery();
                                }
                                String clave = hostActual + nuevoPath;
                                if (!descargadas.contains(clave)) {
                                    descargadas.add(clave);
                                    porDescargar.add(nuevoPath);
                                    System.out.println("   [REDIR ENCOLADO]: " + nuevoPath);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("   [REDIR]: Error al procesar Location: " + e.getMessage());
                    }
                }

                // Parsear HTML y encolar nuevos enlaces si aplica
                if (profundidadActual + 1 < profundidadMaxima && bufferContenido != null && codigoHttpActual == 200) {
                    String html = bufferContenido.toString("UTF-8");
                    encolarEnlaces(html);
                }
                cerrarRecursos(llave, sc);
                return true;
            }

            int bytesAcumulados = 0;
            while (bytesLeidos > 0) {
                buffer.flip(); // Preparamos el buffer para lectura desde el inicio
                bytesAcumulados += archivoSalida.write(buffer);
                bufferContenido.write(buffer.array(), 0, buffer.limit());
                buffer.clear(); // Volvemos a preparar para recibir más datos
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

            // Buscar Location para redirecciones
            for (String linea : lineas) {
                if (linea.toLowerCase().startsWith("location:")) {
                    redireccionPendiente = linea.substring("location:".length()).trim();
                    break;
                }
            }

            headerProcesado = true;
        } catch (Exception e) {
            // Ignorar si no se puede parsear todavía
        }
    }

    private static void encolarEnlaces(String html) {
        try {
            // Buscamos href/src simples en comillas
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)(?:href|src)\\s*=\\s*\\\"([^\\\"]+)\\\"");
            java.util.regex.Matcher m = p.matcher(html);

            URL base = new URL(esquemaActual, hostActual, puertoActual, rutaActual);

            while (m.find()) {
                String enlace = m.group(1).trim();
                if (enlace.isEmpty()) continue;
                if (enlace.startsWith("#") || enlace.startsWith("javascript:")) continue;

                try {
                    URL resuelta = new URL(base, enlace);

                    // Solo mismo host
                    if (!resuelta.getHost().equalsIgnoreCase(hostActual)) {
                        continue;
                    }

                    String path = resuelta.getPath().isEmpty() ? "/" : resuelta.getPath();
                    if (resuelta.getQuery() != null) {
                        path += "?" + resuelta.getQuery();
                    }

                    // Filtrar: solo permitir ciertos tipos de archivo
                    if (!esArchivoPermitido(path)) {
                        continue;
                    }

                    String clave = hostActual + path;
                    if (!descargadas.contains(clave)) {
                        descargadas.add(clave);
                        porDescargar.add(path);
                        System.out.println("   [ENCOLADO]: " + path);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[WARN] No se pudieron parsear enlaces: " + e.getMessage());
        }
    }

    private static boolean esArchivoPermitido(String ruta) {
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
}
