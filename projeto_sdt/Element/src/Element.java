import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Element {
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int MULTICAST_PORT = 5000;
    private static final int HEARTBEAT_INTERVAL = 5000; // Intervalo em milissegundos
    private static final int LEADER_PORT = 4446;

    private final int id; // ID único do elemento
    private final Path elementDirectory;
    private final ConcurrentMap<String, String> documents = new ConcurrentHashMap<>();
    private String leaderId; // Armazena o ID do líder
    private InetAddress leaderAddress;

    public Element(int id) {
        this.id = id;
        this.elementDirectory = Paths.get("element_" + id);

        try {
            if (!Files.exists(elementDirectory)) {
                Files.createDirectories(elementDirectory);
                System.out.println("Diretório criado para Elemento " + id + ": " + elementDirectory.toAbsolutePath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java Element <ID>");
            System.exit(1);
        }

        int id = Integer.parseInt(args[0]);
        new Element(id).start();
    }

    public void start() {
        System.out.println("Elemento " + id + " iniciado.");
        new Thread(this::listenToMulticast).start();
        new Thread(this::sendActiveHeartbeat).start();
        new Thread(this::sendUpdatesToLeader).start();
    }

    private void listenToMulticast() {
        try (MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket.joinGroup(group);

            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Elemento " + id + " recebeu mensagem: " + message);

                processMessage(message, packet.getAddress());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processMessage(String message, InetAddress senderAddress) {
        if (message.startsWith("LEADER:")) {
            leaderId = message.split(":")[1]; // Extraímos o ID do líder
            leaderAddress = senderAddress;
            System.out.println("Elemento " + id + " identificou o líder com ID: " + leaderId);
        } else if (message.startsWith("UPDATE:")) {
            String[] parts = message.split(":", 3);
            if (parts.length < 3) return;

            String fileName = parts[1];
            String content = parts[2];

            documents.put(fileName, content);

            Path filePath = elementDirectory.resolve(fileName);
            try {
                Files.writeString(filePath, content);
                System.out.println("Elemento " + id + " atualizou documento: " + fileName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendActiveHeartbeat() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            while (true) {
                String message = "ACTIVE:" + id; // Envia o ID único
                byte[] buffer = message.getBytes();

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                socket.send(packet);

                System.out.println("Elemento " + id + " respondeu ao heartbeat.");
                Thread.sleep(HEARTBEAT_INTERVAL);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void sendUpdatesToLeader() {
        try (DatagramSocket socket = new DatagramSocket()) {
            while (true) {
                if (leaderAddress == null) {
                    Thread.sleep(HEARTBEAT_INTERVAL); // Aguarda até identificar o líder
                    continue;
                }

                for (String fileName : documents.keySet()) {
                    String content = documents.get(fileName);
                    String message = "UPDATE:" + fileName + ":" + content;

                    byte[] buffer = message.getBytes();
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, LEADER_PORT);

                    socket.send(packet);
                    System.out.println("Elemento " + id + " enviou atualização para o líder: " + fileName);
                }

                Thread.sleep(HEARTBEAT_INTERVAL);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

}
