import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

public class Client {
    private static final int LEADER_PORT = 4446; // Porta do líder

    public static void main(String[] args) {
        System.out.println("Cliente iniciado. Enviando uma lista de mensagens para o líder...");

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress leaderAddress = InetAddress.getByName("127.0.0.1");

            // Lista de mensagens que o cliente enviará
            List<String> messages = List.of(
                    "Mensagem 1 do Cliente",
                    "Mensagem 2 do Cliente",
                    "Mensagem 3 do Cliente"
            );

            for (String message : messages) {
                byte[] buffer = message.getBytes();

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, leaderAddress, LEADER_PORT);
                clientSocket.send(packet);

                System.out.println("Cliente enviou: " + message);
                Thread.sleep(1000); // Envia mensagens com um intervalo de 1 segundo
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
