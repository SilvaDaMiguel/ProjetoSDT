import java.io.IOException;
import java.nio.file.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Client {
    private static final int LEADER_PORT = 4446;
    private static final String DIRECTORY_NAME = "client_files";

    public static void main(String[] args) {
        System.out.println("Cliente iniciado. Monitorando alterações na pasta...");

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress leaderAddress = InetAddress.getByName("127.0.0.1");

            Path directoryPath = Paths.get(System.getProperty("user.dir"), DIRECTORY_NAME);

            if (Files.notExists(directoryPath)) {
                Files.createDirectories(directoryPath);
                System.out.println("Diretório criado: " + directoryPath.toAbsolutePath());
            }

            WatchService watchService = FileSystems.getDefault().newWatchService();
            directoryPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path fileName = (Path) event.context();
                    Path filePath = directoryPath.resolve(fileName);

                    if (Files.isRegularFile(filePath) && fileName.toString().endsWith(".txt")) {
                        String content = Files.readString(filePath);
                        if (content == null) {
                            content = ""; // Garantir que não seja nulo
                        }

                        String message = (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ? "CREATE:" : "UPDATE:")
                                + fileName.toString() + ":" + content;

                        byte[] buffer = message.getBytes();
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, LEADER_PORT);
                        clientSocket.send(packet);

                        System.out.println("Cliente notificou líder sobre " + event.kind() + ": " + fileName.toString());
                    }

                }
                key.reset();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
