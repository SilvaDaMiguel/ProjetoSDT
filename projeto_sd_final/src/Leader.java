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
    private Map<String, Integer> elementPorts = new HashMap<>();


    // Rastrear ACKs recebidos
    private final ConcurrentMap<String, Set<String>> ackTracker = new ConcurrentHashMap<>();


    private static final int ELEMENT_REQUEST_PORT = 4447;



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
        announceLeader();
        new Thread(this::sendHeartbeats).start();
        new Thread(this::listenToMulticast).start();
        new Thread(this::checkActiveElements).start();
        new Thread(this::listenToClients).start();
        new Thread(this::listenToElements).start();
    }


    private void announceLeader() {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String message = "NEW_LEADER:" + id;

            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            multicastSocket.send(packet);

            System.out.println("Novo líder anunciado: " + id);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

                // Ignorar mensagens enviadas pelo próprio líder
                if (message.startsWith("UPDATE:") || message.startsWith("LEADER:")) {
                    String senderId = message.split(":")[1];
                    if (senderId.equals(this.id)) {
                        continue; // Ignorar mensagens do próprio líder
                    }
                }

                if (message.startsWith("ACTIVE:")) {
                    String id = message.split(":")[1];
                    activeElements.put(id, System.currentTimeMillis());
                    System.out.println("Líder recebeu resposta de elemento: " + id);
                }

                if (message.startsWith("LEADER:") || message.startsWith("NEW_LEADER:")) {
                    String receivedLeaderId = message.split(":")[1];

                    if (!receivedLeaderId.equals(this.id)) {
                        System.err.println("Conflito detectado! Outro líder anunciado: " + receivedLeaderId);

                        // Regra de resolução - mantém o líder com ID menor, por exemplo
                        if (this.id.compareTo(receivedLeaderId) > 0) {
                            System.err.println("O líder " + receivedLeaderId + " possui prioridade. Encerrando...");
                            System.exit(1);
                        } else {
                            System.err.println("Mantendo liderança. Ignorando mensagem do líder " + receivedLeaderId);
                        }
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
        // Dividir a mensagem em partes, no máximo 2 para DELETE ou no mínimo 3 para CREATE/UPDATE
        String[] parts = message.split(":", 3);

        // Verificar se temos o mínimo esperado: pelo menos 2 partes
        if (parts.length < 2) {
            System.err.println("Mensagem inválida recebida: " + message);
            return;
        }

        String action = parts[0];
        String fileName = parts[1];

        // Quando a ação for CREATE ou UPDATE, buscamos o conteúdo
        if ("CREATE".equals(action) || "UPDATE".equals(action)) {
            if (parts.length < 3) {
                System.err.println("Conteúdo ausente na mensagem de " + action + ": " + message);
                return;
            }

            String content = parts[2]; // Obter conteúdo
            documentManager.addDocumentVersion(fileName, content);
            saveToFile(fileName, content);
            System.out.println("Documento " + fileName + " salvo no líder.");

            // Envia a atualização para os outros elementos
            sendUpdate(fileName, content);
            ackTracker.put(fileName, ConcurrentHashMap.newKeySet());
        }
        // Se a ação for DELETE, não precisamos do conteúdo
        else if ("DELETE".equals(action)) {
            deleteFile(fileName);
            System.out.println("Documento " + fileName + " removido no líder.");

            // Propagar deleção para os clientes
            sendDeleteNotice(fileName);
            ackTracker.remove(fileName);
        } else {
            System.err.println("Ação desconhecida: " + action);
        }
    }

    private void deleteFile(String fileName) {
        Path filePath = directoryPath.resolve(fileName);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                System.out.println("Documento " + fileName + " removido no líder.");
            } else {
                System.out.println("Documento " + fileName + " não encontrado para remoção.");
            }
        } catch (IOException e) {
            System.err.println("Erro ao tentar remover o documento " + fileName);
            e.printStackTrace();
        }
    }

    private void sendDeleteNotice(String fileName) {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            // Enviar mensagem de deleção para todos os elementos
            String message = "DELETE:" + fileName;
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            multicastSocket.send(packet);

            System.out.println("Líder enviou aviso de remoção: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }




    private void sendUpdate(String fileName, String content) {
        try (MulticastSocket multicastSocket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            // Envia a atualização atual
            String message = "UPDATE:" + fileName + ":" + content;
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            multicastSocket.send(packet);

            // Envia o histórico do documento
            String historyMessage = createHistoryMessage(fileName);
            if (historyMessage != null) {
                buffer = historyMessage.getBytes();
                packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                multicastSocket.send(packet);
            }

            System.out.println("Líder enviou atualização e histórico para: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private String createHistoryMessage(String fileName) {
        try {
            List<String> historyFiles = Files.walk(documentManager.historyDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(fileName + "_v"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            StringBuilder historyBuilder = new StringBuilder("HISTORY:").append(fileName).append(":");
            for (String history : historyFiles) {
                Path filePath = documentManager.historyDirectory.resolve(history);
                String content = Files.readString(filePath);
                historyBuilder.append(history).append("|").append(content).append(";");
            }

            return historyBuilder.toString();
        } catch (IOException e) {
            System.err.println("Erro ao criar mensagem de histórico para: " + fileName);
            e.printStackTrace();
            return null;
        }
    }


    private void receiveAck(String elementId, String documentName) {
        ackTracker.computeIfPresent(documentName, (doc, acks) -> {
            acks.add(elementId);
            if (acks.size() > activeElements.size() / 2) { // Maioria confirmada
                commitDocument(documentName);
            }
            return acks;
        });
    }


    private void listenToElements() {
        try (DatagramSocket elementSocket = new DatagramSocket(ELEMENT_REQUEST_PORT)) {
            System.out.println("Líder aguardando mensagens dos elementos na porta " + ELEMENT_REQUEST_PORT + "...");

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                elementSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Líder recebeu mensagem de elemento: " + message);

                processElementMessage(message, packet.getAddress());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processElementMessage(String message, InetAddress senderAddress) {
        if (message.startsWith("ELEMENT_INIT:")) {
            String[] parts = message.split(":");
            String elementId = parts[1];
            int elementPort = Integer.parseInt(parts[2]);

            // Registrar o elemento
            activeElements.put(elementId, System.currentTimeMillis());
            elementPorts.put(elementId, elementPort);

            System.out.println("Líder adicionou elemento ativo: " + elementId + " na porta: " + elementPort);

            // Enviar documentos ao elemento
            sendDocumentsToElement(senderAddress, elementPort);
        }else if (message.startsWith("ACK:")) {
            String[] parts = message.split(":");
            if (parts.length == 3) {
                String elementId = parts[1];
                String documentName = parts[2];
                receiveAck(elementId, documentName);
            }
        } else {
            System.err.println("Mensagem desconhecida recebida de elemento: " + message);
        }
    }




    private void sendDocumentsToElement(InetAddress elementAddress, int elementPort) {
        // Verifica documentos no diretório principal
        if (!Files.exists(directoryPath)) {
            System.err.println("Erro: O diretório principal não existe!");
            return;
        } else if (!Files.isDirectory(directoryPath)) {
            System.err.println("Erro: O caminho principal não é um diretório!");
            return;
        }

        boolean hasFiles = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath)) {
            for (Path file : stream) {
                if (!Files.isDirectory(file)) { // Apenas arquivos
                    hasFiles = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao tentar ler o diretório principal: " + e.getMessage());
            e.printStackTrace();
        }

        if (hasFiles) {
            System.out.println("Documentos encontrados no diretório principal.");
        }

        // Verifica documentos na pasta de histórico
        Path historyDirectory = documentManager.historyDirectory;
        if (!Files.exists(historyDirectory)) {
            System.err.println("Erro: O diretório de histórico não existe!");
            return;
        } else if (!Files.isDirectory(historyDirectory)) {
            System.err.println("Erro: O caminho de histórico não é um diretório!");
            return;
        }

        hasFiles = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDirectory)) {
            for (Path file : stream) {
                if (!Files.isDirectory(file)) { // Apenas arquivos
                    hasFiles = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao tentar ler o diretório de histórico: " + e.getMessage());
            e.printStackTrace();
        }

        if (hasFiles) {
            System.out.println("Documentos encontrados no diretório de histórico.");
        }

        // Enviar documentos para o elemento
        // Enviar documentos do diretório principal
        sendDocumentsFromDirectory(directoryPath, elementAddress, elementPort, false);

        // Enviar documentos do diretório de histórico
        sendDocumentsFromDirectory(documentManager.historyDirectory, elementAddress, elementPort, true);
    }

    private void sendDocumentsFromDirectory(Path directory, InetAddress elementAddress, int elementPort, boolean isHistory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (!Files.isDirectory(file)) { // Ignorar subdiretórios
                    String fileName = file.getFileName().toString();
                    String content = Files.readString(file);
                    sendDocumentToElement(fileName, content, elementAddress, elementPort, isHistory);
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao tentar ler o diretório: " + directory.toAbsolutePath());
            e.printStackTrace();
        }
    }



    private void sendDocumentToElement(String fileName, String content, InetAddress elementAddress, int elementPort, boolean isHistory) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String documentType = isHistory ? "HISTORY" : "DOCUMENT";
            String message = documentType + ":" + fileName + ":" + content;
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, elementAddress, elementPort);
            socket.send(packet);
            System.out.println("Documento enviado (" + documentType + "): " + fileName);
        } catch (IOException e) {
            System.err.println("Erro ao enviar documento: " + fileName);
            e.printStackTrace();
        }
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
                System.err.println("Erro ao salvar a versão " + version + " do documento " + document);
                e.printStackTrace();
            }
        }

    }
}
