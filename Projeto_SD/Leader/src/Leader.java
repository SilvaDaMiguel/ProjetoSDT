import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

// Classe que representa um Elemento
class Elemento {
    private int lider = 1; // Exemplo de variável de identificação do líder

    public int getLider() {
        return lider;
    }

    public void setLider(int lider) {
        this.lider = lider;
    }
}

// Classe que gerencia o grupo multicast
class Multicast {
    private static final String MULTICAST_GROUP = "224.0.0.1"; // Grupo multicast
    private static final int MULTICAST_PORT = 4448;
    private InetAddress group;

    public Multicast() throws Exception {
        group = InetAddress.getByName(MULTICAST_GROUP);
    }

    public InetAddress getGroup() {
        return group;
    }

    public int getPort() {
        return MULTICAST_PORT;
    }

    public void sendMulticastMessage(String message, DatagramSocket socket) throws Exception {
        byte[] buffer = message.getBytes();
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
        socket.send(packet);
    }
}

// Classe que representa o envio periódico de mensagens (ex.: HEARTBEAT)
class SendTransmitter extends Thread {
    private Multicast multicast;
    private DatagramSocket socket;

    public SendTransmitter(Multicast multicast, DatagramSocket socket) {
        this.multicast = multicast;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String heartbeat = "HEARTBEAT";
                multicast.sendMulticastMessage(heartbeat, socket);
                System.out.println("Líder enviou heartbeat.");
                Thread.sleep(5000); // A cada 5 segundos
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

// Classe principal que atua como o Líder
public class Leader {
    private static final int CLIENT_PORT = 4446; // Porta do cliente

    public static void main(String[] args) {
        System.out.println("Líder iniciado. Enviando heartbeats para os elementos e retransmitindo mensagens do cliente...");

        try (DatagramSocket socket = new DatagramSocket();
             DatagramSocket clientSocket = new DatagramSocket(CLIENT_PORT)) {

            Multicast multicast = new Multicast();

            // Inicia o envio periódico de heartbeats
            SendTransmitter transmitter = new SendTransmitter(multicast, socket);
            transmitter.start();

            // Recebe mensagens do cliente e retransmite para os elementos
            byte[] buffer = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                clientSocket.receive(packet);

                String clientMessage = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Líder recebeu mensagem do cliente: " + clientMessage);

                // Retransmite a mensagem para os elementos
                multicast.sendMulticastMessage(clientMessage, socket);
                System.out.println("Líder retransmitiu mensagem: " + clientMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
