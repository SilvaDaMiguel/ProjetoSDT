import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class Cliente {
    // Endereço multicast onde as mensagens serão recebidas
    private final String multicastAddress = "230.0.0.0";

    // Porta onde o grupo multicast está ouvindo
    private final int port = 8888;

    public void start() {
        // Início do bloco try-with-resources para criar e fechar o socket automaticamente
        try (MulticastSocket socket = new MulticastSocket(port)) {
            // Obtém o endereço do grupo multicast
            InetAddress group = InetAddress.getByName(multicastAddress);

            // Junta-se ao grupo multicast, começando a escutar as mensagens enviadas para o endereço e porta especificados
            socket.joinGroup(group);
            System.out.println("Cliente conectado ao grupo multicast: " + multicastAddress);

            // Loop infinito para receber mensagens
            while (true) {
                // Cria um buffer para armazenar a mensagem recebida
                byte[] buffer = new byte[256];

                // Cria um pacote Datagram para receber dados
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Bloquear a execução até receber uma mensagem do grupo multicast
                socket.receive(packet);

                // Converte para string
                String receivedMessage = new String(packet.getData(), 0, packet.getLength());

                // processar mensagem
                processMessage(receivedMessage);
            }
        } catch (IOException e) {
            // Print aos erros caso existem
            e.printStackTrace();
        }
    }

    //Recber mensagens
    private void processMessage(String message) {
        System.out.println("Mensagem recebida: " + message);
    }

    // Iniciar o cliente
    public static void main(String[] args) {
        Cliente cliente = new Cliente();
        cliente.start(); // Inicia o cliente e conectarr ao grupo multicast
    }
}
