import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;

public class Servidor extends Thread {
    private static final String MULTICAST_ADDRESS = "224.0.0.1";
    private static final int PORT = 4446;
    private static final int INTERVALO_HEARTBEAT = 5000;
    private static final int TIMEOUT_RESP = 2000;
    private static final boolean isLider = true;
    private static final String CAMINHO_ARQUIVO = "documento.txt"; // Caminho do arquivo .txt a ser sincronizado

    @Override
    public void run() {
        Set<String> clientesAtivos = new HashSet<>();
        //HashSet impede a duplicação de IPs

        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);
            //criado para se comunicar com o grupo na rede

            while (true) {
                //Envia o heartbeat
                String mensagem = "HEARTBEAT";
                byte[] buffer = mensagem.getBytes();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, grupo, PORT);
                socket.send(packet);
                System.out.println("Servidor: Heartbeat enviado");

                //Configura o socket para escutar respostas por um intervalo de tempo
                socket.setSoTimeout(TIMEOUT_RESP);
                clientesAtivos.clear();

                //Escuta respostas dos clientes
                while (true) {
                    try {
                        byte[] respostaBuffer = new byte[256];
                        DatagramPacket respostaPacket = new DatagramPacket(respostaBuffer, respostaBuffer.length);
                        socket.receive(respostaPacket);
                        String clienteIP = respostaPacket.getAddress().getHostAddress(); //Guarda o IP do Cliente
                        clientesAtivos.add(clienteIP); //Adiciona á lista de Clientes Ativos
                        System.out.println("Servidor: Cliente ativo - " + clienteIP);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                }

                //Mostra os IPs dos Clientes ativos
                System.out.println("Clientes ativos: " + clientesAtivos);

                //Envia o conteúdo do documento para sincronização, caso haja clientes ativos
                if (isLider && !clientesAtivos.isEmpty()) {
                    String conteudoArquivo = lerConteudoArquivo(CAMINHO_ARQUIVO); //Lê o arquivo
                    String mensagemSync = "SYNC_DATA_DOC:" + conteudoArquivo; //Mensagem com o conteudo
                    byte[] syncBuffer = mensagemSync.getBytes();
                    DatagramPacket syncPacket = new DatagramPacket(syncBuffer, syncBuffer.length, grupo, PORT);
                    socket.send(syncPacket); //envia
                    System.out.println("Servidor: Documento sincronizado enviado a todos os clientes.");
                }

                //Espera o intervalo antes de enviar o próximo heartbeat
                Thread.sleep(INTERVALO_HEARTBEAT);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }

    private String lerConteudoArquivo(String caminhoArquivo) {
        try {
            return new String(Files.readAllBytes(Paths.get(caminhoArquivo)));
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo: " + e.getMessage());
            return "";
        }
    }

    public static void main(String[] args) {
        //Criar um Servidor e iniciá-lo
        Servidor servidor = new Servidor();
        servidor.start();
    }
}
