package org.relay.relaynode.util;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Level;


public class Logger {

    public static java.util.logging.Logger logger;
    public static final String workspacePath = System.getProperty("user.dir");
    public static final String logsPath = workspacePath + "\\logs";

    private static final LocalDateTime now = LocalDateTime.now();
    private static final DateTimeFormatter DTformatter = DateTimeFormatter.ofPattern("HH'h'-mm'm'-ss's'-dd-MMM-yyyy");
    private static final String logDateTime = "log-" + now.format(DTformatter);
    private static final String logFilePath = logsPath + "\\" + logDateTime + ".txt";

    public static void loggerInit() throws IOException {
        logger = java.util.logging.Logger.getLogger("Logger");

        try {
            File logFile = new File(logFilePath);
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            FileHandler fh = new FileHandler(logFilePath, true);

            CustomFormatter formatter = new CustomFormatter();
            fh.setFormatter(formatter);
            logger.addHandler(fh);
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void Log(String message) throws SecurityException, NotInitializedException {
        logger.log(Level.INFO, message);
        //System.out.println(message);
    }
}
