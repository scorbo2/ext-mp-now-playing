package ca.corbett.musicplayer.extensions.nowplaying;

import ca.corbett.ems.client.EMSClient;
import ca.corbett.ems.client.channel.Subscriber;
import ca.corbett.extensions.AppExtensionInfo;
import ca.corbett.extras.properties.AbstractProperty;
import ca.corbett.extras.properties.BooleanProperty;
import ca.corbett.extras.properties.FormFieldGenerationListener;
import ca.corbett.extras.properties.IntegerProperty;
import ca.corbett.extras.properties.PropertiesManager;
import ca.corbett.extras.properties.ShortTextProperty;
import ca.corbett.forms.fields.FormField;
import ca.corbett.musicplayer.AppConfig;
import ca.corbett.musicplayer.Version;
import ca.corbett.musicplayer.actions.ReloadUIAction;
import ca.corbett.musicplayer.audio.AudioMetadata;
import ca.corbett.musicplayer.extensions.MusicPlayerExtension;
import ca.corbett.musicplayer.ui.AudioPanel;
import ca.corbett.musicplayer.ui.AudioPanelListener;
import ca.corbett.musicplayer.ui.UIReloadable;
import ca.corbett.musicplayer.ui.VisualizationTrackInfo;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import java.awt.Dimension;
import java.net.ConnectException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * This is an extension for the MusicPlayer application that will connect to an EMS server
 * and broadcast the currently playing track title and artist to the NOW_PLAYING channel.
 * <p>References:</p>
 * <ul>
 *     <li><A HREF="https://github.com/scorbo2/musicplayer">MusicPlayer</A>
 *     <li><A HREF="https://github.com/scorbo2/ems">EMS</A>
 * </ul>
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class NowPlayingExtension extends MusicPlayerExtension implements UIReloadable, AudioPanelListener {
    private static final Logger log = Logger.getLogger(NowPlayingExtension.class.getName());

    private static final String PROP_HOST = "Now Playing.EMS Server.hostname";
    private static final String PROP_PORT = "Now Playing.EMS Server.port";
    private static final String PROP_CHANNEL = "Now Playing.EMS Server.channel";
    private static final String PROP_IDENTITY = "Now Playing.EMS Server.playerName";
    private static final String PROP_STARTUP = "Now Playing.Broadcast.startup";
    private static final String PROP_TRACK_START = "Now Playing.Broadcast.trackStart";
    private static final String PROP_IDLE = "Now Playing.Broadcast.idle";
    private static final String PROP_SHUTDOWN = "Now Playing.Broadcast.shutdown";

    private final AppExtensionInfo extInfo;

    private String emsHost = "localhost";
    private int emsPort = 1975;
    private final String emsChannel = "NOW_PLAYING";
    private String playerName = Version.NAME;

    private boolean includeStartup = true;
    private boolean includeTrackStart = true;
    private boolean includeIdle = false;
    private boolean includeShutdown = true;

    private Subscriber subscriber;

    public NowPlayingExtension() {
        extInfo = AppExtensionInfo.fromExtensionJar(getClass(),
                                                    "/ca/corbett/musicplayer/extensions/nowplaying/extInfo.json");
        if (extInfo == null) {
            throw new RuntimeException("NowPlayingExtension: can't parse extInfo.json from jar resources!");
        }
        subscriber = null;

        suppressEMSConnectExceptions();
    }

    @Override
    public AppExtensionInfo getInfo() {
        return extInfo;
    }

    @Override
    public void loadJarResources() {

    }

    @Override
    protected List<AbstractProperty> createConfigProperties() {
        includeStartup = true;
        includeTrackStart = true;
        includeIdle = false;
        includeShutdown = true;

        List<AbstractProperty> list = new ArrayList<>();

        list.add(new ShortTextProperty(PROP_HOST, "EMS Host:", emsHost, 15));
        list.add(buildPortProperty());
        list.add(new ShortTextProperty(PROP_CHANNEL, "Channel:", emsChannel, 15).setInitiallyEditable(false));
        list.add(new ShortTextProperty(PROP_IDENTITY, "Identify as:", playerName, 15));

        list.add(new BooleanProperty(PROP_STARTUP, "Broadcast on app startup", includeStartup));
        list.add(new BooleanProperty(PROP_TRACK_START, "Broadcast track start", includeTrackStart));
        list.add(new BooleanProperty(PROP_IDLE, "Broadcast when going idle", includeIdle));
        list.add(new BooleanProperty(PROP_SHUTDOWN, "Broadcast on app startup", includeShutdown));

        return list;
    }

    @Override
    public void onActivate() {
        ReloadUIAction.getInstance().registerReloadable(this);
        AudioPanel.getInstance().addAudioPanelListener(this);
        reloadUI();
    }

    @Override
    public void onDeactivate() {
        ReloadUIAction.getInstance().unregisterReloadable(this);
        AudioPanel.getInstance().removeAudioPanelListener(this);
        if (subscriber != null) {
            if (includeShutdown) {
                subscriber.broadcast(emsChannel, playerName + " shutting down.");
            }
            subscriber.disconnect();
            subscriber = null;
        }
    }

    private void connect() {
        if (subscriber != null) {
            subscriber.disconnect();
        }
        subscriber = new Subscriber();
        if (!subscriber.connect(emsHost, emsPort, emsChannel)) {
            log.warning("Unable to connect to EMS host on " + emsHost + ":" + emsPort
                            + " - NowPlaying feature will be disabled.");
            subscriber = null;
            return;
        }
        log.info("NowPlaying: connected to EMS host on " + emsHost + ":" + emsPort);
        if (includeStartup) {
            subscriber.broadcast(emsChannel, playerName + " starting up!");
        }
    }

    @Override
    public void reloadUI() {
        PropertiesManager propsManager = AppConfig.getInstance().getPropertiesManager();

        String newHost = ((ShortTextProperty)propsManager.getProperty(PROP_HOST)).getValue();
        int newPort = ((IntegerProperty)propsManager.getProperty(PROP_PORT)).getValue();
        playerName = ((ShortTextProperty)propsManager.getProperty(PROP_IDENTITY)).getValue();
        includeStartup = ((BooleanProperty)propsManager.getProperty(PROP_STARTUP)).getValue();
        includeShutdown = ((BooleanProperty)propsManager.getProperty(PROP_SHUTDOWN)).getValue();
        includeTrackStart = ((BooleanProperty)propsManager.getProperty(PROP_TRACK_START)).getValue();
        includeIdle = ((BooleanProperty)propsManager.getProperty(PROP_IDLE)).getValue();

        if (!emsHost.equals(newHost) || emsPort != newPort || subscriber == null) {
            emsHost = newHost;
            emsPort = newPort;
            connect();
        }
    }

    @Override
    public void stateChanged(AudioPanel sourcePanel, AudioPanel.PanelState state) {
        if (subscriber == null) {
            return;
        }

        if (state == AudioPanel.PanelState.IDLE && includeIdle) {
            subscriber.broadcast(emsChannel, playerName + " now idle.");
        }

        else if (state == AudioPanel.PanelState.PLAYING && includeTrackStart) {
            AudioMetadata meta = sourcePanel.getAudioData().getMetadata();
            String title = (meta.getTitle() == null || meta.getTitle()
                                                           .isBlank()) ? "(untitled track)" : meta.getTitle();
            String author = (meta.getAuthor() == null || meta.getAuthor().isBlank()) ? "(unknown)" : meta.getAuthor();
            subscriber.broadcast(emsChannel, "[" + playerName + "] now playing: \"" + title + "\" by " + author);
        }
    }

    @Override
    public void audioLoaded(AudioPanel sourcePanel, VisualizationTrackInfo trackInfo) {
        // ignored
    }

    /**
     * By default, JSpinner uses a formatted text field to display numbers. In my locale, that means
     * using comma separators in long numbers, like "1,975" instead of "1975". This is mildly annoying,
     * because we're dealing with port numbers, which (in my opinion anyway) look better without commas.
     * So, we will access the underlying JSpinner and tweak it a little.
     */
    private AbstractProperty buildPortProperty() {
        IntegerProperty prop = new IntegerProperty(PROP_PORT, "EMS Port:", emsPort, 1025, 65534, 1);
        prop.addFormFieldGenerationListener(new FormFieldGenerationListener() {
            @Override
            public void formFieldGenerated(AbstractProperty property, FormField formField) {
                if (formField.getFieldComponent() instanceof JSpinner spinner) {
                    // Access the spinner's editor, when it is the default editor implementation,
                    // and set it to use a NumberFormat that doesn't use grouping (i.e. no commas):
                    if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
                        JFormattedTextField tf = editor.getTextField();
                        NumberFormatter formatter = new NumberFormatter(new DecimalFormat("#"));
                        formatter.setValueClass(Integer.class);
                        tf.setFormatterFactory(new DefaultFormatterFactory(formatter));
                    }

                    // Also adjust the size a bit, since the default never seems to be wide enough:
                    spinner.setPreferredSize(new Dimension(80, 22));
                }
            }
        });

        return prop;
    }

    /**
     * The EMS client library will catch and log a ConnectException if it's unable to connect
     * to a given EMS server. This is poor design - it should propagate that exception to us, the caller,
     * so that we can decide how to handle it. Specifically, I don't want to see scary stack traces
     * in our application log just because the EMS server is down. So, we will try to selectively
     * suppress ConnectExceptions, while still allowing all other exceptions to be logged as normal.
     */
    private void suppressEMSConnectExceptions() {
        // Install the filter on the root logger's handlers, since that's where
        // records ultimately land regardless of which child logger emitted them
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            handler.setFilter(record -> {
                // If this handler already has a filter, we want to work together with it:
                final Filter existingFilter = handler.getFilter();

                // Only intercept records from EMSClient specifically
                if (!EMSClient.class.getName().equals(record.getLoggerName())) {
                    return true; // not our concern, let it through
                }

                // Only suppress ConnectException - we want other exceptions to be logged as normal:
                Throwable thrown = record.getThrown();
                if (thrown != null && isCausedBy(thrown, ConnectException.class)) {
                    return false; // suppress
                }

                // As a safe fallback, in case the Exception wasn't attached, look for the specific exception
                // message from an EMS connection failure, which should be unique enough to avoid false positives:
                String message = record.getMessage();
                if (message != null && message.contains("Unable to connect to EMS server")) {
                    return false; // suppress
                }

                // Otherwise, let it through (including any other exceptions from EMSClient):
                return existingFilter == null ? true : existingFilter.isLoggable(record);
            });
        }
    }

    /**
     * Helper method to check if the given Throwable or any of its causes is an instance of the targetType.
     *
     * @param thrown     The Throwable to check.
     * @param targetType The type of Throwable to look for in the cause chain.
     * @return true if the thrown Throwable or any of its causes is an instance of targetType, false otherwise.
     */
    private static boolean isCausedBy(Throwable thrown, Class<? extends Throwable> targetType) {
        Throwable current = thrown;
        while (current != null) {
            if (targetType.isInstance(current)) {
                return true;
            }
            // Avoid infinite loops from circular causes (rare but possible)
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
