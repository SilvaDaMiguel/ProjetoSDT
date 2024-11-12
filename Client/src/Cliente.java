import java.io.IOException;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class Cliente extends Thread {
    private static final String MULTICAST_ADDRESS = "224.0.0.1";
    private static final int PORT = 4446;
    private static final String CAMINHO_ARQUIVO_LOCAL = "documento_sincronizado.txt"; // Caminho para o arquivo local sincronizado

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(grupo); //junta-se ao grupoda rede


            while (true) {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                //Recebe a mensagem do servidor
                socket.receive(packet);
                String mensagem = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Cliente: Mensagem recebida - " + mensagem);

                //Verifica a mensagem que foi enviada pelo servidor
                if ("HEARTBEAT".equals(mensagem)) {
                    //Envia uma resposta ao servidor
                    String resposta = "RESPONSE";
                    byte[] respostaBytes = resposta.getBytes();
                    DatagramPacket respostaPacket = new DatagramPacket(respostaBytes, respostaBytes.length, packet.getAddress(), packet.getPort());
                    socket.send(respostaPacket);
                    System.out.println("Cliente: Resposta enviada ao servidor");

                } else if (mensagem.startsWith("SYNC_DATA_DOC:")) {
                    //Extrai o conteúdo do documento e salva localmente
                    String conteudoDocumento = mensagem.substring("SYNC_DATA_DOC:".length());
                    salvarConteudoDocumento(conteudoDocumento);
                    System.out.println("Cliente: Documento sincronizado atualizado e salvo localmente.");
                }
            }
        } catch (IOException e) {
            System.err.println("Erro no cliente: " + e.getMessage());
        }
    }

    private void salvarConteudoDocumento(String conteudo) {
        try (FileWriter writer = new FileWriter(CAMINHO_ARQUIVO_LOCAL)) {
            writer.write(conteudo);
        } catch (IOException e) {
            System.err.println("Erro ao salvar o documento sincronizado: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        //Cria o cliente
        Cliente cliente = new Cliente();
        cliente.start();
    }
}
