import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

// Classe que representa um Elemento
class Elemento {
    private int id;
    private long lastHeartbeat;
    private static final int TIMEOUT = 10000; // Timeout de 10 segundos para verificar o líder

    public Elemento(int id) {
        this.id = id;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public int getId() {
        return id;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public boolean isLeaderActive() {
        return System.currentTimeMillis() - lastHeartbeat <= TIMEOUT;
    }
}

// Classe para gerenciar o recebimento de mensagens multicast
class MulticastReceiver extends Thread {
    private static final String MULTICAST_GROUP = "224.0.0.1"; // Grupo multicast
    private static final int MULTICAST_PORT = 4448; // Porta fixa para multicast
    private Elemento elemento;

    public MulticastReceiver(Elemento elemento) {
        this.elemento = elemento;
    }

    @Override
    public void run() {
        try (MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket.joinGroup(group);

            byte[] buffer = new byte[256];
            System.out.println("Elemento " + elemento.getId() + " conectado ao grupo multicast.");

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                processMessage(message);

                if (!elemento.isLeaderActive()) {
                    System.err.println("Elemento " + elemento.getId() + ": Não recebeu heartbeat do líder. Considerando o líder inativo!");
                    break;
                }
            }

            multicastSocket.leaveGroup(group);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processMessage(String message) {
        if (message.equals("HEARTBEAT")) {
            elemento.updateHeartbeat();
            System.out.println("Elemento " + elemento.getId() + " recebeu heartbeat do líder.");
        } else {
            System.out.println("Elemento " + elemento.getId() + " recebeu mensagem: " + message);
        }
    }
}

// Classe principal para inicializar o Elemento
public class Element {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Por favor, informe um argumento para diferenciar os elementos (Ex: 1, 2, 3).");
            System.exit(1);
        }

        int elementNumber = Integer.parseInt(args[0]); // Identificação do elemento
        Elemento elemento = new Elemento(elementNumber);
        MulticastReceiver receiver = new MulticastReceiver(elemento);

        receiver.start();
    }
}
