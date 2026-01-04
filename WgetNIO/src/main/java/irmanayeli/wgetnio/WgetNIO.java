package irmanayeli.wgetnio;

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
    private static String directorioSalida;
    private static String hostActual;
    private static String rutaActual;
    private static int puertoActual;
    private static int profundidadActual;
    private static int profundidadMaxima;
    private static Set<String> descargadas = new HashSet<>();
    private static Queue<String> porDescargar = new LinkedList<>();

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
            puertoActual = url.getPort() == -1 ? (url.getProtocol().equals("https") ? 443 : 80) : url.getPort();
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
                // Crear archivo con nombre basado en la ruta
                String nombreArchivo = rutaActual.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (nombreArchivo.isEmpty() || nombreArchivo.equals("_")) {
                    nombreArchivo = "index.html";
                } else if (!nombreArchivo.contains(".")) {
                    nombreArchivo += ".html";
                }
                
                String rutaCompleta = directorioSalida + "\\" + nombreArchivo;
                archivoSalida = new FileOutputStream(rutaCompleta).getChannel();
                System.out.println("   [ARCHIVO]: " + rutaCompleta);
            }

            int bytesLeidos = sc.read(buffer);

            // -1 indica que el servidor terminó de enviar datos
            if (bytesLeidos == -1) {
                System.out.println("3. [FIN]: Descarga de " + rutaActual + " completa.");
                cerrarRecursos(llave, sc);
                return true;
            }

            int bytesAcumulados = 0;
            while (bytesLeidos > 0) {
                buffer.flip(); // Preparamos el buffer para lectura desde el inicio
                bytesAcumulados += archivoSalida.write(buffer);
                buffer.clear(); // Volvemos a preparar para recibir más datos
                bytesLeidos = sc.read(buffer);
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
