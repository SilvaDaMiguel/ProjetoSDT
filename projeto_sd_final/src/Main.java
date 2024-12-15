import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java Main <tipo> [ID]");
            System.out.println("tipo: 'leader', 'element' ou 'client'");
            System.out.println("ID: Inteiro único para elementos");
            System.exit(1);
        }

        String type = args[0];
        if (type.equalsIgnoreCase("no")) {
            if (args.length < 2) {
                System.out.println("Uso: java Main element <ID>");
                System.exit(1);
            }
            int id = Integer.parseInt(args[1]);
            new Element(id).start();
        } else if (type.equalsIgnoreCase("client")) {
            System.out.println("Iniciando como cliente...");
            Client.main(new String[0]); // Chama o método `main` do Client diretamente
        } else {
            System.out.println("Tipo desconhecido: " + type);
            System.exit(1);
        }
    }
}
