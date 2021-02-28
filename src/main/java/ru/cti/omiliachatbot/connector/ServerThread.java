package ru.cti.omiliachatbot.connector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.SneakyThrows;
import ru.cti.omiliachatbot.actions.Request;
import ru.cti.omiliachatbot.config.AppConfig;
import ru.cti.omiliachatbot.utils.Log;
import ru.cti.omiliachatbot.utils.Prompt;
import ru.cti.omiliachatbot.utils.PromptMapper;

import java.awt.image.AreaAveragingScaleFilter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

public class ServerThread extends Thread {

    private Socket socket;
    private DataOutputStream dos;
    private DataInputStream dis;
    private Log log;
    static AppConfig appConfig = new AppConfig();
    static String connectionURL = appConfig.getOmiliaConnectionURL();
    static String dialogURL = appConfig.getOmiliaDialogURL();
    static JsonObject response;
    static JsonObject welcomeMessageResponse;
    static Request request = new Request();

    public ServerThread(Socket socket, DataOutputStream dos, DataInputStream dis, Log log) {
        this.socket = socket;
        this.dos = dos;
        this.dis = dis;
        this.log = log;
    }

    @SneakyThrows
    @Override
    public void run() {
        String entry;
        ArrayList<String> answer;
        ArrayList<String> toClient = new ArrayList<>();
        try {
            welcomeMessageResponse = dialogInit();
            String dialogId = getDialogId(welcomeMessageResponse);

            //Пересылаем приветственный ролик
            ArrayList<String> welcomePrompts = showBotMessages(welcomeMessageResponse, dialogId);
            log.loggingMessage("Connection accepted. DialogID: " + dialogId + "Welcome message: >>> " + welcomePrompts.get(2) + "\n");
            dos.writeUTF(welcomePrompts.get(0));
            dos.flush();

            // начинаем диалог с подключенным клиентом в цикле, пока сокет не закрыт(пока поток не прерван)
            while (!this.isInterrupted()) {
                // ждем получения данных клиента
                entry = dis.readUTF();
                // логгируем сообщение клиента
                log.loggingMessage("DialogID: " + dialogId + " User utterance >>>" + entry + "\n");

                response = request.makeRequest(dialogURL, entry, dialogId);
                answer = showBotMessages(response, dialogId);
                log.loggingMessage("DialogID: " + dialogId + " Omilia answer >>> " + answer.get(2) + "\n");

                // если условие окончания работы не верно - продолжаем работу
                if (answer.get(1).equalsIgnoreCase("transfer")) {
                    this.interrupt();
                }
                dos.writeUTF(answer.get(0));
                dos.flush();
            }
            // если условие выхода - верно выключаем соединения
            // закрываем сначала каналы сокета !
            dis.close();
            dos.close();
            // потом закрываем сам сокет общения на стороне сервера!
            socket.close();
        } catch (Exception ex) {
            log.loggingMessage(ex.getMessage());
        }
    }

    private JsonObject dialogInit() throws Exception {
        //Сделать запрос "Start new dialog"
        //Вывести на экран сообщения бота после Start new dialog - приветствие
        String utterance = null;
        String dialogId = null;

        response = request.makeRequest(connectionURL, utterance, dialogId);
        return response;
    }

    private static String getDialogId(JsonObject response) {
        return response.get("dialogId").toString();
    }

    private static ArrayList<String> showBotMessages(JsonObject response, String dialogId) throws Exception {

        ArrayList<String> parsedAnswer = new ArrayList<>();
        JsonElement actionType = response.getAsJsonObject("action");
        ArrayList<Prompt> prompts = getPromptsFromOmiliaReply(response);
        ArrayList<Prompt> promptsAfterPing = new ArrayList<>();
        //если Omilia сначала говорит announce, а потом должна сделать ask, но без "пинка" молчит
        if (getAnswerType(actionType).equals("ANNOUNCEMENT")) {
            try {
                promptsAfterPing = checkForASK(dialogId);
                System.out.println((promptsAfterPing.get(0).getContent()));
            } catch (IOException ex) {
                System.out.println(ex.getMessage());
            }
        }
        prompts.addAll(promptsAfterPing);
        parsedAnswer.add(preparePromptsOutput(prompts));
        parsedAnswer.add(getAnswerType(actionType));
        parsedAnswer.add(actionType.toString());
        return parsedAnswer;
    }

    private static ArrayList<Prompt> getPromptsFromOmiliaReply(JsonObject response) throws IOException {
        ArrayList<Prompt> promptList = new ArrayList<>();
        ArrayList<Prompt> prompts = PromptMapper.convert(response.getAsJsonObject("action").getAsJsonObject("message").getAsJsonArray("prompts").toString());
        prompts.stream().forEach(item -> promptList.add(item));
        return promptList;
    }

    private static String preparePromptsOutput(ArrayList<Prompt> prompts) {
        StringBuilder output = new StringBuilder();
        for (Prompt prompt : prompts) {
            output.append(prompt.getContent());
        }
        return output.toString();
    }

    private static String getAnswerType(JsonElement answerType) {
        return answerType.getAsJsonObject().get("type").toString().replaceAll("\"", "");
    }

    private static ArrayList<Prompt> checkForASK(String dialogId) throws Exception {
        response = request.makeRequest(dialogURL, "[noinput]", dialogId);
        return getPromptsFromOmiliaReply(response);
    }
}
