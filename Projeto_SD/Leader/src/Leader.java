import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Leader {
    private static final int CLIENT_PORT = 4446;
    private static final int MULTICAST_PORT = 4448;
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int ELEMENT_REQUEST_PORT = 4447;
    private static final int ELEMENT_ACK_PORT = 4450;

    private final DocumentManager documentManager = new DocumentManager();
    private final ConcurrentMap<String, List<InetAddress>> ackTracker = new ConcurrentHashMap<>();
    private volatile boolean isRunning = true;

    public static void main(String[] args) {
        new Leader().start();
    }

    public void start() {
        System.out.println("Líder iniciado.");

        try (DatagramSocket clientSocket = new DatagramSocket(CLIENT_PORT);
             DatagramSocket elementRequestSocket = new DatagramSocket(ELEMENT_REQUEST_PORT);
             DatagramSocket ackSocket = new DatagramSocket(ELEMENT_ACK_PORT);
             DatagramSocket multicastSocket = new DatagramSocket()) {

            new Thread(() -> handleClientRequests(clientSocket, multicastSocket)).start();
            new Thread(() -> handleElementRequests(elementRequestSocket)).start();
            new Thread(() -> handleElementACKs(ackSocket, multicastSocket)).start();
            new Thread(this::sendHeartbeats).start();

            Thread.sleep(60000); // Manter o líder ativo durante 60s
            isRunning = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // Lidar com pedidos do cliente
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

                    // Atualizar o documento no gestor de documentos
                    documentManager.addDocument(documentId + ":" + version);

                    Path filePath = Paths.get(documentId + ".txt");
                    Files.writeString(filePath, version);

                    System.out.println("Líder atualizou documento: " + documentId);

                    // Enviar atualizações por multicast
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

    // Lidar com ACKs dos elementos
    private void handleElementACKs(DatagramSocket ackSocket, DatagramSocket multicastSocket) {
        try {
            byte[] buffer = new byte[1024];
            while (isRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    ackSocket.setSoTimeout(5000); // Timeout de 5 segundos
                    ackSocket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength());
                    InetAddress elementAddress = packet.getAddress();

                    if (message.startsWith("ACK:")) {
                        String documentId = message.split(":")[1];
                        ackTracker.putIfAbsent(documentId, Collections.synchronizedList(new ArrayList<>()));
                        List<InetAddress> ackList = ackTracker.get(documentId);

                        if (!ackList.contains(elementAddress)) {
                            ackList.add(elementAddress);
                            System.out.println("Líder recebeu ACK de " + elementAddress + " para documento: " + documentId);

                            // Verificar se a maioria foi atingida
                            if (ackList.size() >= getMajority()) {
                                System.out.println("Maioria atingida para documento: " + documentId);
                                sendCommit(documentId, multicastSocket);
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout ao esperar ACK para documento. Reenviando atualizações pendentes...");
                    resendPendingUpdates(multicastSocket);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Reenvia atualizações pendentes para elementos
    private void resendPendingUpdates(DatagramSocket multicastSocket) {
        List<String> updates = documentManager.createSendStructure();
        for (String update : updates) {
            sendMulticast(update, multicastSocket);
        }
        System.out.println("Atualizações pendentes reenviadas.");
    }


    // Retorna o número necessário para atingir a maioria
    private int getMajority() {
        // Simulação de maioria para teste
        return 2; // Ajustar consoante o número de elementos esperado
    }

    // Envia uma mensagem de COMMIT aos elementos
    private void sendCommit(String documentId, DatagramSocket multicastSocket) {
        String commitMessage = "COMMIT:" + documentId;
        sendMulticast(commitMessage, multicastSocket);
        System.out.println("Líder enviou COMMIT para documento: " + documentId);
    }

    // Envia uma mensagem multicast para todos os elementos
    private void sendMulticast(String message, DatagramSocket socket) {
        try {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);
            System.out.println("Mensagem multicast enviada: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Lidar com pedidos de sincronização dos elementos
    private void handleElementRequests(DatagramSocket socket) {
        try {
            byte[] buffer = new byte[1024];

            while (isRunning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                if (message.equals("REQUEST_DOCUMENTS")) {
                    InetAddress address = packet.getAddress();
                    int port = packet.getPort();

                    System.out.println("Líder recebeu pedido de sincronização de " + address);

                    // Enviar a lista de documentos confirmados
                    List<String> documents = documentManager.createSendStructure();
                    for (String doc : documents) {
                        DatagramPacket responsePacket = new DatagramPacket(
                                doc.getBytes(), doc.length(), address, port
                        );
                        socket.send(responsePacket);
                    }

                    // Indicar o final da sincronização
                    String endMessage = "END_SYNC";
                    DatagramPacket endPacket = new DatagramPacket(
                            endMessage.getBytes(), endMessage.length(), address, port
                    );
                    socket.send(endPacket);

                    System.out.println("Líder completou envio de sincronização para " + address);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void sendHeartbeats() {
        try (DatagramSocket socket = new DatagramSocket()) {
            while (isRunning) {
                Thread.sleep(5000); // Intervalo de 5 segundos
                String heartbeatMessage = "HEARTBEAT";
                sendMulticast(heartbeatMessage, socket);
                System.out.println("Líder enviou heartbeat.");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }



// Classe para gestão de documentos
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