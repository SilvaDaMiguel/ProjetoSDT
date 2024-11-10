import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class Cliente extends Thread {
    private static final String MULTICAST_ADDRESS = "224.0.0.1"; // Endereço IP multicast
    private static final int PORT = 4446; // Porta de comunicação

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(grupo);

            while (true) {
                byte[] buffer = new byte[256];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Recebe a mensagem do servidor
                socket.receive(packet);
                String mensagem = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Cliente: Mensagem recebida - " + mensagem);

                if ("HEARTBEAT".equals(mensagem)) {
                    // Envia uma resposta ao servidor
                    String resposta = "RESPONSE";
                    byte[] respostaBytes = resposta.getBytes();
                    DatagramPacket respostaPacket = new DatagramPacket(respostaBytes, respostaBytes.length, packet.getAddress(), packet.getPort());
                    socket.send(respostaPacket);
                    System.out.println("Cliente: Resposta enviada ao servidor");
                } else if ("SYNC_DATA".equals(mensagem)) {
                    // Processa a sincronização de dados
                    System.out.println("Cliente: Iniciando sincronização de dados");
                    // Aqui pode ser implementada a lógica de sincronização de dados
                }
            }
        } catch (IOException e) {
            System.err.println("Erro no cliente: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Cliente cliente = new Cliente();
        cliente.start();
    }
}
