package ca.corbett.musicplayer.extensions.nowplaying;

import ca.corbett.ems.client.channel.Subscriber;
import ca.corbett.extensions.AppExtensionInfo;
import ca.corbett.extras.properties.AbstractProperty;
import ca.corbett.extras.properties.BooleanProperty;
import ca.corbett.extras.properties.IntegerProperty;
import ca.corbett.extras.properties.PropertiesManager;
import ca.corbett.extras.properties.ShortTextProperty;
import ca.corbett.musicplayer.AppConfig;
import ca.corbett.musicplayer.Version;
import ca.corbett.musicplayer.actions.ReloadUIAction;
import ca.corbett.musicplayer.audio.AudioData;
import ca.corbett.musicplayer.extensions.MusicPlayerExtension;
import ca.corbett.musicplayer.ui.AudioPanel;
import ca.corbett.musicplayer.ui.AudioPanelListener;
import ca.corbett.musicplayer.ui.UIReloadable;
import ca.corbett.musicplayer.ui.VisualizationTrackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class NowPlayingExtension extends MusicPlayerExtension implements UIReloadable, AudioPanelListener {
    Logger log = Logger.getLogger(NowPlayingExtension.class.getName());

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
        extInfo = AppExtensionInfo.fromExtensionJar(getClass(), "/ca/corbett/musicplayer/extensions/nowplaying/extInfo.json");
        if (extInfo == null) {
            throw new RuntimeException("NowPlayingExtension: can't parse extInfo.json from jar resources!");
        }
        subscriber = null;
    }

    @Override
    public AppExtensionInfo getInfo() {
        return extInfo;
    }

    @Override
    protected List<AbstractProperty> createConfigProperties() {
        List<AbstractProperty> list = new ArrayList<>();

        list.add(new ShortTextProperty("Now Playing.EMS Server.hostname", "EMS Host:", emsHost, 15));
        list.add(new IntegerProperty("Now Playing.EMS Server.port", "EMS Port:", emsPort, 1025, 65534, 1));
        ShortTextProperty channelProp = new ShortTextProperty("Now Playing.EMS Server.channel", "Channel:", emsChannel, 15);
        channelProp.setInitiallyEditable(false);
        list.add(channelProp);
        list.add(new ShortTextProperty("Now Playing.EMS Server.playerName", "Identify as:", playerName, 15));

        list.add(new BooleanProperty("Now Playing.Broadcast.startup", "Broadcast on app startup", includeStartup));
        list.add(new BooleanProperty("Now Playing.Broadcast.trackStart", "Broadcast track start", includeTrackStart));
        list.add(new BooleanProperty("Now Playing.Broadcast.idle", "Broadcast when going idle", includeIdle));
        list.add(new BooleanProperty("Now Playing.Broadcast.shutdown", "Broadcast on app startup", includeShutdown));

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
        if (! subscriber.connect(emsHost, emsPort, emsChannel)) {
            log.warning("Unable to connect to EMS host on "+emsHost+":"+emsPort);
            subscriber = null;
            return;
        }
        if (includeStartup) {
            subscriber.broadcast(emsChannel, playerName + " starting up!");
        }
    }

    @Override
    public void reloadUI() {
        PropertiesManager propsManager = AppConfig.getInstance().getPropertiesManager();

        String newHost = ((ShortTextProperty)propsManager.getProperty("Now Playing.EMS Server.hostname")).getValue();
        int newPort = ((IntegerProperty)propsManager.getProperty("Now Playing.EMS Server.port")).getValue();
        playerName = ((ShortTextProperty)propsManager.getProperty("Now Playing.EMS Server.playerName")).getValue();
        includeStartup = ((BooleanProperty)propsManager.getProperty("Now Playing.Broadcast.startup")).getValue();
        includeShutdown = ((BooleanProperty)propsManager.getProperty("Now Playing.Broadcast.shutdown")).getValue();
        includeTrackStart = ((BooleanProperty)propsManager.getProperty("Now Playing.Broadcast.trackStart")).getValue();
        includeIdle = ((BooleanProperty)propsManager.getProperty("Now Playing.Broadcast.idle")).getValue();

        if (! emsHost.equals(newHost) || emsPort != newPort || subscriber == null) {
            connect();
            emsHost = newHost;
            emsPort = newPort;
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
            AudioData.Metadata meta = sourcePanel.getAudioData().getMetadata();
            String title = (meta.title == null || meta.title.isBlank()) ? "(untitled track)" : meta.title;
            String author = (meta.author == null || meta.author.isBlank()) ? "(unknown)" : meta.author;
            subscriber.broadcast(emsChannel, "[" + playerName + "] now playing: \""+title+"\" by "+author);
        }
    }

    @Override
    public void audioLoaded(AudioPanel sourcePanel, VisualizationTrackInfo trackInfo) {
        // ignored
    }
}
