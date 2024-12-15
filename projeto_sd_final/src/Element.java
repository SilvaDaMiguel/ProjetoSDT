import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Element {
    private static final String MULTICAST_GROUP = "224.0.0.1";
    private static final int MULTICAST_PORT = 5000;
    private static final int HEARTBEAT_INTERVAL = 3000; // Intervalo em milissegundos
    private static final int LEADER_PORT = 4446;
    private static final long TIMEOUT = 9000;
    private static final long TIMEOUT_leader = 10000;
    private volatile boolean isLeader = false; // Adiciona um indicador de liderança



    private volatile boolean running = true;




    private volatile long lastLeaderHeartbeat = System.currentTimeMillis();
    private final ConcurrentMap<String, Long> activeElements = new ConcurrentHashMap<>();

    private final int id; // ID único do elemento
    private final Path elementDirectory;
    private final Path historyDirectory;
    private final ConcurrentMap<String, String> documents = new ConcurrentHashMap<>();
    private String leaderId; // Armazena o ID do líder
    private InetAddress leaderAddress;


    private static final int ELEMENT_REQUEST_PORT = 4447;
    private boolean hasSentInitializationMessage = false;

    private DatagramSocket socket; // Reutilizado para enviar e receber



    public Element(int id) {
        this.id = id;
        this.elementDirectory = Paths.get("element_" + id);
        this.historyDirectory = elementDirectory.resolve("history");

        try {
            if (!Files.exists(elementDirectory)) {
                Files.createDirectories(elementDirectory);
                System.out.println("Diretório criado para Elemento " + id + ": " + elementDirectory.toAbsolutePath());
            }


            if (!Files.exists(historyDirectory)) {
                Files.createDirectories(historyDirectory);
                System.out.println("Diretório de histórico criado para Elemento " + id + ": " + historyDirectory.toAbsolutePath());
            }


            this.socket = new DatagramSocket(0); // Cria um socket com porta dinâmica
            System.out.println("Elemento " + id + " escutando na porta dinâmica: " + socket.getLocalPort());
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
        new Thread(this::listenForDocuments).start();
        new Thread(this::checkActiveElements).start();
        new Thread(this::monitorLeader).start();
    }

    private void monitorLeader() {
        while (!isLeader && running) { // Adiciona verificação de liderança
            try {
                Thread.sleep(5000); // Chama sleep dentro do try-catch

                long currentTime = System.currentTimeMillis();

                // Caso não exista líder
                if (leaderId == null) {
                    System.out.println("Nenhum líder identificado. Iniciando processo de eleição.");
                    initiateLeaderElection();
                }
                // Caso o líder esteja identificado, verifica o heartbeat
                else if (currentTime - lastLeaderHeartbeat > TIMEOUT_leader) {
                    System.err.println("Líder " + leaderId + " não respondeu no tempo esperado. Considerando-o inativo.");
                    leaderId = null;
                    leaderAddress = null;
                    initiateLeaderElection();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Encerrando monitorLeader pois o elemento é líder.");
    }



    private void initiateLeaderElection() {
        System.out.println("Elemento " + id + " iniciando eleição de líder.");
        try (DatagramSocket electionSocket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String electionMessage = "ELECTION:" + id;

            byte[] buffer = electionMessage.getBytes();
            DatagramPacket electionPacket = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            electionSocket.send(electionPacket);

            // Aguarde respostas
            Thread.sleep(3000);

            // Avalia respostas recebidas
            boolean higherPriorityExists = activeElements.keySet().stream()
                    .map(Integer::parseInt)
                    .anyMatch(otherId -> otherId > id);

            if (!higherPriorityExists) {
                System.out.println("Sem prioridade maior, tornando-se líder.");
                transformToLeader();
            } else {
                System.out.println("Prioridade maior detectada. Aguardando novo líder.");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }





    private void checkActiveElements() {
        while (running && !isLeader) { // Verifica se não é líder
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




    private void listenForDocuments() {
        try {
            byte[] buffer = new byte[1024];
            while (!isLeader && running) { // Adiciona verificação de liderança
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Elemento " + id + " recebeu mensagem direta: " + message);

                processDirectMessage(message);
            }

            System.out.println("Encerrando listenForDocuments pois o elemento é líder.");
        } catch (SocketException e) {
            if (running) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }





    private void processDirectMessage(String message) {
        if (message.startsWith("DOCUMENT:")) {
            String[] parts = message.split(":", 3);
            String fileName = parts[1];
            String content = parts[2];
            saveToDirectory(fileName, content, false); // Diretório principal
        } else if (message.startsWith("HISTORY:")) {
            String[] parts = message.split(":", 3);
            String fileName = parts[1];
            String content = parts[2];
            saveToDirectory(fileName, content, true); // Diretório histórico
        }else if (message.startsWith("ELECTION:")) {
            int senderId = Integer.parseInt(message.split(":")[1]);
            if (senderId < id) {
                System.out.println("Elemento " + id + " iniciando nova eleição por receber ELECTION de " + senderId);
                initiateLeaderElection();
            } else if (senderId > id) {
                System.out.println("Elemento " + id + " reconhecendo " + senderId + " como potencial líder.");
            }
        } else if (message.startsWith("COORDINATOR:")) {
            String newLeaderId = message.split(":")[1];
            if (!newLeaderId.equals(String.valueOf(id))) {
                // Atualiza o líder e interrompe funções de liderança
                leaderId = newLeaderId;
                running = false; // Para as tarefas locais
                System.out.println("Novo líder detectado: " + leaderId);
            }
        }
        else {
            System.err.println("Mensagem desconhecida do líder: " + message);
        }
    }


    private void restartAsLeader() {
        transformToLeader();
    }



    private void transformToLeader() {
        System.out.println("Elemento " + id + " transformando-se em líder...");

        // Atualiza o estado para líder
        isLeader = true;
        running = false; // Sinaliza para encerrar threads atuais

        // Aguarda um pouco para garantir o encerramento das threads antigas
        try {
            Thread.sleep(HEARTBEAT_INTERVAL);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Reinicia como líder
        System.out.println("Elemento " + id + " agora é o líder.");
        new Leader().start(); // Inicia as funções do líder
    }





    private void saveToDirectory(String fileName, String content, boolean isHistory) {
        Path directory = isHistory ? historyDirectory : elementDirectory;

        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            Path filePath = directory.resolve(fileName);
            Files.writeString(filePath, content);
            System.out.println("Documento salvo em " + (isHistory ? "histórico" : "diretório principal") + ": " + filePath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Erro ao salvar documento: " + fileName);
            e.printStackTrace();
        }
    }




    private void listenToMulticast() {
        try (MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket.joinGroup(group);

            byte[] buffer = new byte[1024];

            while (!isLeader && running) { // Adiciona verificação de liderança
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Elemento " + id + " recebeu mensagem: " + message);

                processMessage(message, packet.getAddress());
            }

            System.out.println("Encerrando listenToMulticast pois o elemento é líder.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private void sendInitializationMessage() {
        try {
            String message = "ELEMENT_INIT:" + id + ":" + socket.getLocalPort();

            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, ELEMENT_REQUEST_PORT);
            socket.send(packet);

            System.out.println("Elemento " + id + " enviou mensagem de inicialização ao líder.");
            hasSentInitializationMessage = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void processMessage(String message, InetAddress senderAddress) {
        if (message.startsWith("LEADER:")) {
            leaderId = message.split(":")[1];
            leaderAddress = senderAddress;
            System.out.println("Elemento " + id + " identificou o líder com ID: " + leaderId);
            lastLeaderHeartbeat = System.currentTimeMillis();

            if (!hasSentInitializationMessage) {
                sendInitializationMessage();
                hasSentInitializationMessage = true; // Atualiza a flag após enviar a mensagem
            }


        } else if (message.startsWith("UPDATE:")) {
            String[] parts = message.split(":", 3);
            if (parts.length < 3) return;

            String fileName = parts[1];
            String content = parts[2];

            // Salvar temporariamente e enviar ACK
            documents.put(fileName, content);
            sendAck(fileName);

        }
        else if (message.startsWith("COMMIT:")) {
            String fileName = message.split(":")[1];
            applyUpdate(fileName);
        } else if (message.startsWith("HISTORY:")) {
            String[] parts = message.split(":", 3);
            if (parts.length < 3) return;

            String fileName = parts[1];
            String[] historyEntries = parts[2].split(";");

            for (String entry : historyEntries) {
                if (entry.isEmpty()) continue;
                String[] entryParts = entry.split("\\|", 2);
                if (entryParts.length < 2) continue;

                String versionFile = entryParts[0];
                String content = entryParts[1];

                saveHistoryFile(fileName, versionFile, content);
            }
        }
        else if (message.startsWith("ACTIVE:")) {
            String id = message.split(":")[1];
            activeElements.put(id, System.currentTimeMillis());
        }
        else if (message.startsWith("DELETE:")) { // Novo código para deletar arquivos
            String fileName = message.split(":")[1];
            deleteDocument(fileName);
        }


    }

    private void deleteDocument(String fileName) {
        Path filePath = elementDirectory.resolve(fileName);
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

    private void saveHistoryFile(String fileName, String versionFile, String content) {
        Path historyDir = elementDirectory.resolve("history");
        try {
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
            }

            Path filePath = historyDir.resolve(versionFile);
            Files.writeString(filePath, content);
            System.out.println("Elemento " + id + " sincronizou histórico: " + fileName + " - " + versionFile);
        } catch (IOException e) {
            System.err.println("Erro ao salvar histórico no elemento " + id + " para: " + fileName);
            e.printStackTrace();
        }
    }

    private void applyUpdate(String fileName) {
        String content = documents.get(fileName);
        if (content != null) {
            Path filePath = elementDirectory.resolve(fileName);
            try {
                Files.writeString(filePath, content);
                System.out.println("Elemento " + id + " aplicou commit para documento: " + fileName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private void sendAck(String fileName) {
        if (leaderAddress == null) {
            System.out.println("Endereço do líder desconhecido. Não foi possível enviar ACK.");
            return;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            // Construção da mensagem ACK
            String ackMessage = "ACK:" + id + ":" + fileName;
            byte[] buffer = ackMessage.getBytes();

            // Envia o pacote via unicast para o líder
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, ELEMENT_REQUEST_PORT);
            socket.send(packet);
            System.out.println("Elemento " + id + " enviou ACK ao líder para o documento: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private void sendActiveHeartbeat() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            while (running && !isLeader) { // Adiciona verificação de estado
                String message = "ACTIVE:" + id;
                byte[] buffer = message.getBytes();

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                socket.send(packet);

                System.out.println("Elemento " + id + " enviou heartbeat.");
                Thread.sleep(HEARTBEAT_INTERVAL);
            }
        } catch (IOException | InterruptedException e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }


}
