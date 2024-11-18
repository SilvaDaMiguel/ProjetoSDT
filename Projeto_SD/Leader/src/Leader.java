import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Leader {
    private static final int CLIENT_PORT = 4446;
    private static final int MULTICAST_PORT = 4448;
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int ELEMENT_REQUEST_PORT = 4447;

    private final DocumentManager documentManager = new DocumentManager();
    private volatile boolean isRunning = true;

    public static void main(String[] args) {
        new Leader().start();
    }

    public void start() {
        System.out.println("Líder iniciado.");

        try (DatagramSocket clientSocket = new DatagramSocket(CLIENT_PORT);
             DatagramSocket elementRequestSocket = new DatagramSocket(ELEMENT_REQUEST_PORT);
             DatagramSocket multicastSocket = new DatagramSocket()) {

            // Threads para gerenciar funcionalidades
            new Thread(() -> handleClientRequests(clientSocket, multicastSocket)).start();
            new Thread(() -> handleElementRequests(elementRequestSocket)).start();
            new Thread(this::sendHeartbeats).start();

            // Simulação de execução por 60 segundos
            Thread.sleep(60000);
            isRunning = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleClientRequests(DatagramSocket clientSocket, DatagramSocket multicastSocket) {
        try {
            byte[] buffer = new byte[1024];

            while (isRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                clientSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Líder recebeu mensagem: " + message);

                if (message.contains(":")) {
                    String[] parts = message.split(":");
                    String documentId = parts[0];
                    String version = parts[1];
                    documentManager.addDocument(documentId + ":" + version);

                    // Salva a nova versão no arquivo local
                    Path filePath = Paths.get(documentId + ".txt");
                    Files.writeString(filePath, version);

                    System.out.println("Líder atualizou documento: " + documentId);

                    // Envia atualizações via multicast
                    List<String> updates = documentManager.createSendStructure();
                    for (String update : updates) {
                        sendMulticast(update, multicastSocket);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleElementRequests(DatagramSocket elementRequestSocket) {
        try {
            byte[] buffer = new byte[1024];

            while (isRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                elementRequestSocket.receive(packet);

                String request = new String(packet.getData(), 0, packet.getLength());
                if (request.equals("REQUEST_DOCUMENTS")) {
                    System.out.println("Líder recebeu solicitação de sincronização de um elemento.");

                    InetAddress elementAddress = packet.getAddress();
                    int elementPort = packet.getPort();

                    // Envia a lista de documentos para o elemento
                    List<String> documents = documentManager.getDocumentsClone();
                    for (String doc : documents) {
                        DatagramPacket responsePacket = new DatagramPacket(
                                doc.getBytes(),
                                doc.getBytes().length,
                                elementAddress,
                                elementPort
                        );
                        elementRequestSocket.send(responsePacket);
                    }

                    // Envia mensagem de término de sincronização
                    DatagramPacket endPacket = new DatagramPacket(
                            "END_SYNC".getBytes(),
                            "END_SYNC".getBytes().length,
                            elementAddress,
                            elementPort
                    );
                    elementRequestSocket.send(endPacket);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendHeartbeats() {
        try (DatagramSocket socket = new DatagramSocket()) {
            while (isRunning) {
                sendMulticast("HEARTBEAT", socket);
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMulticast(String message, DatagramSocket socket) {
        try {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            byte[] buffer = message.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);

            System.out.println("Líder enviou multicast: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Classe interna para gerenciar documentos de forma thread-safe.
     */
    private static class DocumentManager {
        private final List<String> documents = Collections.synchronizedList(new ArrayList<>());

        public synchronized void addDocument(String document) {
            documents.add(document);
            System.out.println("Documento adicionado: " + document);
        }

        public synchronized void removeDocument(String document) {
            if (documents.remove(document)) {
                System.out.println("Documento removido: " + document);
            } else {
                System.out.println("Documento não encontrado: " + document);
            }
        }

        public synchronized List<String> getDocumentsClone() {
            return new ArrayList<>(documents);
        }

        public synchronized List<String> createSendStructure() {
            List<String> sendStructure = new ArrayList<>();
            for (String doc : documents) {
                sendStructure.add("UPDATE:" + doc);
            }
            return sendStructure;
        }
    }
}
