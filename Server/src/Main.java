import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

// Classe Elemento representa um nó
class Elemento {
    int lider = 1; // Identificação estática do líder
    boolean isLeader;

    // Construtor para definir se o elemento atual é o líder
    Elemento(boolean isLeader) {
        this.isLeader = isLeader;
    }
}

class SendTransmitter extends Thread {
    private final String[] listaMsgs = {"m1", "m2", "m3"};
    
    @Override
    public void run() {
        // Usa try-with-resources para abrir e fechar o MulticastSocket automaticamente
        try (MulticastSocket socket = new MulticastSocket()) {
            //adresses
            InetAddress group = InetAddress.getByName("230.0.0.0");
            System.out.println("Iniciando envio de mensagens...");

            // Loop infinito para enviar as mensagenss
            while (true) {
                // Envia cada mensagem a lista das mensagens
                for (String msg : listaMsgs) {
                    // Converte a mensagem para bytes para enviar no pacote
                    byte[] buffer = msg.getBytes();

                    // Cria um DatagramPacket e envua
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, 8888);

                    // Envia o pacote usando o MulticastSocket
                    socket.send(packet);
                    System.out.println("Mensagem enviada: " + msg); // Log para mostrar a mensagem enviada
                }

                // Pausa por 5 segundos
                Thread.sleep(5000);
            }
        } catch (IOException | InterruptedException e) {
            // moistra erros se existerem
            e.printStackTrace();
        }
    }
}

// Classe principal do programa
public class Main {
    public static void main(String[] args) {
        // Cria o elemento líder. Define que este nó será o líder do grupo
        Elemento leader = new Elemento(true);

        // Cria uma instância de SendTransmitter que será responsável pelo envio de mensagens
        SendTransmitter transmitter = new SendTransmitter();

        // Verifica se este elemento é o líder
        if (leader.isLeader) {
            // Se for o líder, inicia a thread de envio de mensagens
            transmitter.start();
        }
    }
}
