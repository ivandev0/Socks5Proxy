import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.StringTokenizer;

public class Socks5Proxy {

    private enum ERRORS {
        ALL_OK,
        SOCKS_SERVER_ERROR,
        CONNECTION_DENIED,
        NETWORK_UNAVAILABLE,
        HOST_UNAVAILABLE,
        CONNECTION_FAILURE,
        TTL_EXPIRATION,
        BAD_REQUEST,
        ADDRESS_TYPE_UNAVAILABLE,
        NONE
    };

    static class Attachment {
        private static final byte SOCKS_VERSION = 0x05;
        private static final byte[] OK = new byte[] { SOCKS_VERSION, 0x00 };
        private static final byte[] FAIL_GREETINGS = new byte[]{ SOCKS_VERSION, (byte) 0xFF };
        private static byte[] RESPONSE_TEMPLATE = new byte[] { SOCKS_VERSION, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        boolean isFirstGreeting = true;
        boolean closeAfterWrite = false;
        /**
         * Буфер для чтения, в момент проксирования становится буфером для
         * записи для ключа хранимого в peer
         */
        ByteBuffer in;
        /**
         * Буфер для записи, в момент проксирования равен буферу для чтения для
         * ключа хранимого в peer
         */
        ByteBuffer out;
        /**
         * Куда проксируем
         */
        SelectionKey peer;

        Attachment(){
            in = ByteBuffer.allocate(8192);
            out = ByteBuffer.allocate(8192);
        }

        private void close(){
            if(peer != null) {
                Attachment peerKey = ((Attachment) peer.attachment());
                if (peerKey != null && peerKey.peer != null) {
                    peerKey.peer.cancel();
                }
                peer.cancel();
            }
        }

        private void responseToGreetings (boolean accept, SelectionKey key){
            closeAfterWrite = !accept;
            if(accept){
                out.put(OK).flip();
            } else {
                out.put(FAIL_GREETINGS).flip();
            }
            key.interestOps(SelectionKey.OP_WRITE);
            isFirstGreeting = false;
        }

        private void responseToHeader (ERRORS responseCode, SelectionKey key, byte[] ip, byte[] port){
            if(responseCode == ERRORS.NONE)
                return;

            byte[] response = RESPONSE_TEMPLATE;
            response[1] = (byte)responseCode.ordinal();
            System.arraycopy(ip, 0, response, 4, ip.length);
            System.arraycopy(port, 0, response, 8, port.length-2);

            if(responseCode != ERRORS.ALL_OK){
                closeAfterWrite = true;
                out.put(response).flip();
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                closeAfterWrite = false;
                in.put(response).flip();

                out = ((Attachment) peer.attachment()).in;
                ((Attachment) peer.attachment()).out = in;

                peer.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                key.interestOps(0);
            }
        }


    }

    public static void main(String[] args) {
        int port = 1080;
        String host = "127.0.0.1";

        // Открываем серверный канал
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             Selector selector = Selector.open()){
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(host, port));

            // Регистрация в селекторе
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            // Основной цикл работу неблокирующего сервер
            while (selector.select() > -1) {
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    System.out.println(key);
                    if (key.isValid()) {
                        // Обработка всех возможнных событий ключа
                        try {
                            if (key.isAcceptable()) {
                                // Принимаем соединение
                                accept(key);
                            } else if (key.isConnectable()) {
                                // Устанавливаем соединение
                                connect(key);
                            } else if (key.isReadable()) {
                                // Читаем данные
                                read(key);
                            } else if (key.isWritable()) {
                                // Пишем данные
                                write(key);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            close(key);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void accept(SelectionKey key) throws IOException {
        // Приняли
        SocketChannel newChannel = ((ServerSocketChannel) key.channel()).accept();
        // Неблокирующий
        newChannel.configureBlocking(false);
        // Регистрируем в селекторе
        newChannel.register(key.selector(), SelectionKey.OP_READ);
    }

    private static void read(SelectionKey key) throws IOException{
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = ((Attachment) key.attachment());
        if (attachment == null) {
            // инициализируем буферы
            key.attach(attachment = new Attachment());
        }

        if (channel.read(attachment.in) < 1) {
            // -1 - разрыв;
            // 0 - нет места в буфере
            close(key);
        } else if (attachment.peer == null) {
            // если нету второго конца значит мы читаем заголовок
            System.out.println("header " + Arrays.toString(attachment.in.array()));
            if(attachment.isFirstGreeting){
                //читаем либо первый пакет
                attachment.responseToGreetings(readGreetings(attachment), key);
            } else {
                //либо читаем второй пакет, с адресом
                attachment.responseToHeader(readHeader(key, attachment), key, new byte[4], new byte[2]);
            }
            attachment.in.clear();
        } else {
            System.out.println("data " + Arrays.toString(attachment.in.array()));
            // ну а если мы проксируем, то добавляем ко второму концу интерес записать
            attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_WRITE);
            // а у первого убираем интерес прочитать, т.к пока не записали
            // текущие данные, читать ничего не будем
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
            // готовим буфер для записи
            attachment.in.flip();
            ((Attachment)attachment.peer.attachment()).out = attachment.in;
        }
    }
    private static ERRORS readHeader(SelectionKey key, Attachment attachment) throws IOException{
        byte[] ar = attachment.in.array();

        if(ar.length < 10){
            return ERRORS.BAD_REQUEST;
        }
        //версия обязательно пятая
        if(ar[0] != 5 || ar[1] != 1 || ar[2] != 0){
            return ERRORS.BAD_REQUEST;
        }

        //обязательно ipv4
        if(ar[3] != 1){
            return ERRORS.ADDRESS_TYPE_UNAVAILABLE;
        }

        // Создаём соединение
        SocketChannel peer = SocketChannel.open();
        peer.configureBlocking(false);
        // Получаем из канала адрес и порт
        byte[] addr = new byte[] { ar[4], ar[5], ar[6], ar[7] };
        int p = (((int)ar[8] & 0xFF) << 8) + ((int)ar[9] & 0xFF);
        // Начинаем устанавливать соединение
        peer.connect(new InetSocketAddress(InetAddress.getByAddress(addr), p));
        SelectionKey peerKey = peer.register(key.selector(), SelectionKey.OP_CONNECT);
        // Обмен ключами
        attachment.peer = peerKey;
        Attachment peerAttachment = new Attachment();
        peerAttachment.peer = key;
        peerKey.attach(peerAttachment);

        return ERRORS.NONE;
    }
    private static boolean readGreetings(Attachment attachment){
        byte[] ar = attachment.in.array();

        if(ar.length < 2){
            return false;
        }
        //версия обязательно пятая
        if(ar[0] != 5){
            return false;
        }
        //количесто методов должно быть больше 0
        int methodNumber = ar[1];
        if(methodNumber <= 0){
            return false;
        }
        //поддерживается единственный метод
        for (int i = 2; i < methodNumber + 2; i++){
            if(ar[i] == 0){
                return true;
            }
        }
        return false;
    }

    private static void write(SelectionKey key) throws IOException {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());
        System.out.println("to write " + Arrays.toString(attachment.out.array()));

        if (channel.write(attachment.out) == -1) {
            close(key);
        } else if (attachment.out.remaining() ==  0 ) {
            if(attachment.closeAfterWrite){
                //посылали ошибку и закрываем соединение
                close(key);
            } else {
                if (attachment.peer == null) {
                    //ждем посылку с адресом
                    key.interestOps(SelectionKey.OP_READ);
                } else {
                    // если всё записано, чистим буфер
                    attachment.out.clear();
                    // Добавялем ко второму концу интерес на чтение
                    attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_READ);
                    // А у своего убираем интерес на запись
                    key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
                }
            }
        }

    }

    private static void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = ((Attachment) key.attachment());
        // Завершаем соединение
        channel.finishConnect();
        // Создаём буфер и отвечаем OK
        StringBuilder sb = new StringBuilder(channel.getLocalAddress().toString());
        sb = sb.replace(0,1, "");

        StringTokenizer st = new StringTokenizer(sb.toString(), ":");
        InetAddress ip = InetAddress.getByName(st.nextToken());
        byte[] bytesIp = ip.getAddress();
        byte[] port = ByteBuffer.allocate(4).putInt(Integer.valueOf(st.nextToken())).array();

        attachment.responseToHeader(ERRORS.ALL_OK, key, bytesIp, port);
    }

    private static void close(SelectionKey key) throws IOException {
        key.cancel();
        key.channel().close();
        Attachment attachment = (Attachment) key.attachment();
        if(attachment != null) {
            attachment.close();
        }
    }
}
