package irmanayeli.wgetnio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class WgetNIO {

    public static void main(String[] args) {
        try {
            // 1. EL "TIMBRE": El Selector nos avisará cuando algo pase
            Selector selector = Selector.open();

            // 2. EL "CANAL": Abrimos la conexión hacia el servidor
            SocketChannel canal = SocketChannel.open();
            canal.configureBlocking(false); // <--- Esto lo hace No Bloqueante
            
            // Intentamos conectar a Google (puerto 80 es HTTP)
            canal.connect(new InetSocketAddress("www.google.com", 80));

            // 3. EL REGISTRO: Le decimos al selector que vigile cuando el canal se CONECTE
            canal.register(selector, SelectionKey.OP_CONNECT);

            System.out.println("Iniciando Wget No Bloqueante...");

            while (true) {
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
                        gestionarLectura(llave);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void gestionarConexion(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // Terminamos el proceso de conexión
        if (sc.finishConnect()) {
            System.out.println("1. [CONECTADO]: Ya podemos hablar con el servidor.");
            // Ahora le decimos que queremos ESCRIBIR osea mandar el GET
            sc.register(selector, SelectionKey.OP_WRITE);
        }
    }

    private static void gestionarEscritura(SelectionKey llave, Selector selector) throws Exception {
        SocketChannel sc = (SocketChannel) llave.channel();
        // La petición HTTP básica 
        String peticion = "GET / HTTP/1.1\r\n" +
                         "Host: www.google.com\r\n" +
                         "Connection: close\r\n\r\n";
        
        ByteBuffer buffer = ByteBuffer.wrap(peticion.getBytes());
        sc.write(buffer);
        System.out.println("2. [PETICIÓN ENVIADA]: Esperando respuesta...");
        
        // Ya pedimos ahora queremos LEER lo que nos manden
        sc.register(selector, SelectionKey.OP_READ);
    }

    private static void gestionarLectura(SelectionKey llave) {
        
        // Por ahora solo se imprime esto para probar
        System.out.println("3. [LEYENDO]: El servidor está mandando datos...");
    }
}
