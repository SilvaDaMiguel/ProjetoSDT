import java.io.IOException;
import java.nio.file.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Client {
    private static final int LEADER_PORT = 4446;
    private static final String FILE_NAME = "documento.txt";

    public static void main(String[] args) {
        System.out.println("Cliente iniciado. Monitorando alterações no documento...");

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress leaderAddress = InetAddress.getByName("127.0.0.1");

            Path filePath = Paths.get(System.getProperty("user.dir"), FILE_NAME);

            if (Files.notExists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Ficheiro criado: " + filePath.toAbsolutePath());
            }

            WatchService watchService = FileSystems.getDefault().newWatchService();
            filePath.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().toString().equals(filePath.getFileName().toString())) {
                        String content = Files.readString(filePath);
                        String documentId = "doc1";
                        String message = documentId + ":" + content;

                        byte[] buffer = message.getBytes();
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, LEADER_PORT);
                        clientSocket.send(packet);

                        System.out.println("Cliente enviou nova versão do documento para o líder: " + content);
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
