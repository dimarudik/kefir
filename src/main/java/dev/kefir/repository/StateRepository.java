package dev.kefir.repository;

import dev.kefir.model.BotState;

import java.io.*;
import java.util.Properties;

public class StateRepository {
    private static final String STATE_DIR = "state";

    public void save(String ticker, BotState state) {
        Properties props = new Properties();
        props.setProperty("supportLevel", String.valueOf(state.supportLevel));
        props.setProperty("resistanceLevel", String.valueOf(state.resistanceLevel));
        props.setProperty("trailingStopPrice", String.valueOf(state.trailingStopPrice));
        props.setProperty("lastAtr", String.valueOf(state.lastAtr));
        props.setProperty("isLocked", String.valueOf(state.isLocked));
        props.setProperty("isLongActive", String.valueOf(state.isLongActive));
        props.setProperty("isShortActive", String.valueOf(state.isShortActive));
        props.setProperty("lastClosedPrice", String.valueOf(state.lastClosedPrice));
        props.setProperty("longEntryPrice", String.valueOf(state.longEntryPrice));
        props.setProperty("shortEntryPrice", String.valueOf(state.shortEntryPrice));
        props.setProperty("totalProfit", String.valueOf(state.totalProfit));
        props.setProperty("status", state.status);

        File dir = new File(STATE_DIR);
        if (!dir.exists()) dir.mkdir();

        try (OutputStream out = new FileOutputStream(STATE_DIR + "/" + ticker + ".properties")) {
            props.store(out, "Bot state: " + ticker);
        } catch (IOException e) {
            System.err.println("Ошибка сохранения состояния для " + ticker + ": " + e.getMessage());
        }
    }

    public BotState load(String ticker) {
        File file = new File(STATE_DIR + "/" + ticker + ".properties");
        if (!file.exists()) return null;

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
            BotState state = new BotState();
            state.supportLevel = Double.parseDouble(props.getProperty("supportLevel", "0"));
            state.resistanceLevel = Double.parseDouble(props.getProperty("resistanceLevel", "0"));
            state.trailingStopPrice = Double.parseDouble(props.getProperty("trailingStopPrice", "0"));
            state.lastAtr = Double.parseDouble(props.getProperty("lastAtr", "0"));
            state.isLocked = Boolean.parseBoolean(props.getProperty("isLocked", "false"));
            state.isLongActive = Boolean.parseBoolean(props.getProperty("isLongActive", "false"));
            state.isShortActive = Boolean.parseBoolean(props.getProperty("isShortActive", "false"));
            state.lastClosedPrice = Double.parseDouble(props.getProperty("lastClosedPrice", "0"));
            state.longEntryPrice = Double.parseDouble(props.getProperty("longEntryPrice", "0"));
            state.shortEntryPrice = Double.parseDouble(props.getProperty("shortEntryPrice", "0"));
            state.totalProfit = Double.parseDouble(props.getProperty("totalProfit", "0"));
            state.status = props.getProperty("status", "INITIALIZING");
            return state;
        } catch (IOException e) {
            return null;
        }
    }

}
