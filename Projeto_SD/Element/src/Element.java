import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Element {
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int MULTICAST_PORT = 4448;
    private static final int ELEMENT_REQUEST_PORT = 4447;

    private final int id;
    private final Path elementDirectory;
    private final ConcurrentMap<String, String> documents = new ConcurrentHashMap<>();

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
        syncWithLeader();
    }

    private void syncWithLeader() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress leaderAddress = InetAddress.getByName("127.0.0.1");

            // Solicita a lista de documentos ao líder
            String request = "REQUEST_DOCUMENTS";
            DatagramPacket requestPacket = new DatagramPacket(
                    request.getBytes(),
                    request.getBytes().length,
                    leaderAddress,
                    ELEMENT_REQUEST_PORT
            );
            socket.send(requestPacket);

            // Recebe a lista de documentos
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);

                String message = new String(responsePacket.getData(), 0, responsePacket.getLength());
                if (message.equals("END_SYNC")) {
                    System.out.println("Sincronização com o líder concluída.");
                    break;
                }

                String[] parts = message.split(":");
                String documentId = parts[0];
                String version = parts[1];

                documents.put(documentId, version);
                Path filePath = elementDirectory.resolve(documentId + ".txt");
                Files.writeString(filePath, "Documento: " + documentId + "\nVersão: " + version);

                System.out.println("Elemento " + id + " sincronizou documento: " + documentId);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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

                if (message.startsWith("UPDATE:")) {
                    String[] parts = message.split(":");
                    String documentId = parts[1];
                    String version = parts[2];

                    documents.put(documentId, version);
                    Path filePath = elementDirectory.resolve(documentId + ".txt");
                    Files.writeString(filePath, "Documento: " + documentId + "\nVersão: " + version);

                    System.out.println("Elemento " + id + " atualizou documento: " + filePath);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
