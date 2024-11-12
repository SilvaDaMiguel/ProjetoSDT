import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;

public class Servidor extends Thread {
    private static final String MULTICAST_ADDRESS = "224.0.0.1"; // Endereço IP multicast
    private static final int PORT = 4446; // Porta de comunicação
    private static final int INTERVALO_HEARTBEAT = 5000; // Intervalo de 5 segundos
    private static final int TIMEOUT_RESP = 2000; // Tempo limite para respostas dos clientes (2 segundos)
    private static final boolean isLider = true; // Definir este servidor como líder

    @Override
    public void run() {
        Set<String> clientesAtivos = new HashSet<>();

        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);

            while (true) {
                // Envia o heartbeat
                String mensagem = "HEARTBEAT";
                byte[] buffer = mensagem.getBytes();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, grupo, PORT);
                socket.send(packet);
                System.out.println("Servidor: Heartbeat enviado");

                // Configura o socket para escutar respostas por um intervalo
                socket.setSoTimeout(TIMEOUT_RESP);
                clientesAtivos.clear();

                // Escuta respostas dos clientes
                while (true) {
                    try {
                        byte[] respostaBuffer = new byte[256];
                        DatagramPacket respostaPacket = new DatagramPacket(respostaBuffer, respostaBuffer.length);
                        socket.receive(respostaPacket);
                        String clienteIP = respostaPacket.getAddress().getHostAddress();
                        clientesAtivos.add(clienteIP);
                        System.out.println("Servidor: Cliente ativo - " + clienteIP);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                }

                System.out.println("Clientes ativos: " + clientesAtivos);

                // Se este servidor é o líder e há clientes ativos, envia uma mensagem de sincronização
                if (isLider && !clientesAtivos.isEmpty()) {
                    String mensagemSync = "SYNC_DATA";
                    byte[] syncBuffer = mensagemSync.getBytes();
                    DatagramPacket syncPacket = new DatagramPacket(syncBuffer, syncBuffer.length, grupo, PORT);
                    socket.send(syncPacket);
                    System.out.println("Servidor (Líder): Mensagem de sincronização enviada");
                }

                // Espera o intervalo antes de enviar o próximo heartbeat
                Thread.sleep(INTERVALO_HEARTBEAT);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        //Criar um servidor e iniciá-lo
        Servidor servidor = new Servidor();
        servidor.start();
    }
}
