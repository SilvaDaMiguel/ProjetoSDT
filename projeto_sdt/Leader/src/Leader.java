import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.*;

public class Leader {
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int MULTICAST_PORT = 5000;
    private static final int CLIENT_PORT = 4446;
    private static final int HEARTBEAT_INTERVAL = 3000; // 3 segundos
    private static final int TIMEOUT = 10000; // 10 segundos para considerar um elemento inativo

    private final String id; // ID único do líder
    private final ConcurrentMap<String, Long> activeElements = new ConcurrentHashMap<>();
    private final DocumentManager documentManager = new DocumentManager(); // Gerenciador de documentos
    private final Path directoryPath;

    // Rastrear ACKs recebidos
    private final ConcurrentMap<String, Set<String>> ackTracker = new ConcurrentHashMap<>();

    public Leader() {
        this.id = UUID.randomUUID().toString(); // Geração do ID único
        directoryPath = Paths.get(System.getProperty("user.dir"), "leader_files");
        try {
            if (Files.notExists(directoryPath)) {
                Files.createDirectories(directoryPath);
                System.out.println("Diretório do líder criado: " + directoryPath.toAbsolutePath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        new Leader().start();
    }

    public void start() {
        new Thread(this::sendHeartbeats).start();
        new Thread(this::listenToMulticast).start();
        new Thread(this::checkActiveElements).start();
        new Thread(this::listenToClients).start();
    }

    private void sendHeartbeats() {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String message = "LEADER:" + id;

            while (true) {
                byte[] buffer = message.getBytes();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                multicastSocket.send(packet);

                System.out.println("Líder enviou heartbeat.");
                Thread.sleep(HEARTBEAT_INTERVAL);
            }
        } catch (IOException | InterruptedException e) {
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
                System.out.println("Líder recebeu mensagem: " + message);

                if (message.startsWith("ACTIVE:")) {
                    String id = message.split(":")[1];
                    activeElements.put(id, System.currentTimeMillis());
                    System.out.println("Líder recebeu resposta de elemento: " + id);
                } else if (message.startsWith("ACK:")) {
                    String[] parts = message.split(":");
                    if (parts.length == 3) {
                        String elementId = parts[1];
                        String documentName = parts[2];
                        receiveAck(elementId, documentName);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkActiveElements() {
        while (true) {
            long currentTime = System.currentTimeMillis();
            activeElements.entrySet().removeIf(entry -> currentTime - entry.getValue() > TIMEOUT);

            System.out.println("Elementos ativos: " + activeElements.keySet());
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void listenToClients() {
        try (DatagramSocket clientSocket = new DatagramSocket(CLIENT_PORT)) {
            System.out.println("Líder aguardando mensagens dos clientes...");

            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                clientSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Líder recebeu: " + message);

                processClientMessage(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processClientMessage(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length < 3) {
            System.err.println("Mensagem inválida recebida: " + message);
            return;
        }

        String action = parts[0];
        String fileName = parts[1];
        String content = parts[2];

        if ("CREATE".equals(action) || "UPDATE".equals(action)) {
            documentManager.addDocumentVersion(fileName, content);
            saveToFile(fileName, content);
            System.out.println("Documento " + fileName + " salvo no líder.");

            // Envia atualização para os elementos
            sendUpdate(fileName, content);
            ackTracker.put(fileName, ConcurrentHashMap.newKeySet());
        } else {
            System.err.println("Ação desconhecida: " + action);
        }
    }

    private void sendUpdate(String fileName, String content) {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String message = "UPDATE:" + fileName + ":" + content;
            byte[] buffer = message.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            multicastSocket.send(packet);

            System.out.println("Líder enviou atualização: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveAck(String elementId, String documentName) {
        ackTracker.computeIfPresent(documentName, (doc, acks) -> {
            acks.add(elementId);
            if (acks.size() > activeElements.size() / 2) {
                commitDocument(documentName);
            }
            return acks;
        });
    }

    private void commitDocument(String fileName) {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String commitMessage = "COMMIT:" + fileName;
            byte[] buffer = commitMessage.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            multicastSocket.send(packet);

            System.out.println("Líder enviou commit: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveToFile(String fileName, String content) {
        Path filePath = directoryPath.resolve(fileName);
        try {
            Files.writeString(filePath, content);
            System.out.println("Documento salvo fisicamente: " + filePath.toAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Classe para gestão de documentos com histórico
    private static class DocumentManager {
        private final ConcurrentMap<String, Integer> documentVersionMap = new ConcurrentHashMap<>();
        private final Path historyDirectory = Paths.get(System.getProperty("user.dir"), "leader_files", "history");

        public DocumentManager() {
            try {
                if (Files.notExists(historyDirectory)) {
                    Files.createDirectories(historyDirectory);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public synchronized void addDocumentVersion(String document, String content) {
            int version = documentVersionMap.getOrDefault(document, 0) + 1;
            documentVersionMap.put(document, version);

            Path versionFile = historyDirectory.resolve(document + "_v" + version + ".txt");
            try {
                Files.writeString(versionFile, content);
                System.out.println("Versão " + version + " do documento " + document + " salva.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
