package com.kasp.rbw.instance;

import com.andrei1058.bedwars.api.arena.IArena;
import com.kasp.rbw.EmbedType;
import com.kasp.rbw.GameState;
import com.kasp.rbw.PickingMode;
import com.kasp.rbw.RBW;
import com.kasp.rbw.config.Config;
import com.kasp.rbw.database.SQLGameManager;
import com.kasp.rbw.database.SQLite;
import com.kasp.rbw.instance.cache.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class Game {

    private int number;

    // discord
    private Guild guild;

    private Category channelsCategory;
    private Category vcsCategory;

    private String channelID;
    private String vc1ID;
    private String vc2ID;

    private Queue queue;

    // game
    private List<Player> players;
    // player, people in party
    private Map<Player, Integer> playersInParties;

    private Player captain1;
    private Player captain2;
    private Player currentCaptain;

    private List<Player> team1;
    private List<Player> team2;
    private List<Player> remainingPlayers;

    private GameState state;

    private boolean casual;
    private GameMap map;
    private Player mvp;
    private Member scoredBy; // this acts as voidedBy if game was voided

    private TimerTask closingTask;

    // IF THE GAME WAS SCORED

    private HashMap<Player, Integer> eloGain; // used for =undogame

    public Game(List<Player> players, Queue queue) {

        guild = RBW.getGuild();
        this.players = new ArrayList<>(players);
        this.queue = queue;

        this.number = SQLGameManager.getGameSize();

        this.team1 = new ArrayList<>();
        this.team2 = new ArrayList<>();

        this.state = GameState.STARTING;

        this.casual = queue.isCasual();

        List<GameMap> validMaps = new ArrayList<>();
        for (GameMap map : MapCache.getMaps().values())
            if (map.getMaxPlayers() == players.size()/2)
                validMaps.add(map);

        Collections.shuffle(validMaps);
        this.map = validMaps.get(0);

        this.channelsCategory = guild.getCategoryById(Config.getValue("game-channels-category"));
        this.vcsCategory = guild.getCategoryById(Config.getValue("game-vcs-category"));

        channelID = channelsCategory.createTextChannel(Config.getValue("game-channel-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" + queue.getPlayersEachTeam())).complete().getId();
        vc1ID = vcsCategory.createVoiceChannel(Config.getValue("game-vc-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" +queue.getPlayersEachTeam()).replaceAll("%team%", "1")).setUserlimit(queue.getPlayersEachTeam()).complete().getId();
        vc2ID = vcsCategory.createVoiceChannel(Config.getValue("game-vc-names").replaceAll("%number%", number + "").replaceAll("%mode%", queue.getPlayersEachTeam() + "v" +queue.getPlayersEachTeam()).replaceAll("%team%", "2")).setUserlimit(queue.getPlayersEachTeam()).complete().getId();

        Collections.shuffle(players);
        this.remainingPlayers = new ArrayList<>(players);

        if (queue.getPickingMode() == PickingMode.CAPTAINS) {
            this.captain1 = players.get(0);
            this.captain2 = players.get(1);

            currentCaptain = captain1;
            if (captain1.getElo() > captain2.getElo()) {
                currentCaptain = captain2;
            }

            this.team1.add(captain1);
            this.team2.add(captain2);

            try {
                guild.moveVoiceMember(guild.getMemberById(captain1.getID()), guild.getVoiceChannelById(vc1ID)).queue(null, new ErrorHandler().ignore(ErrorResponse.USER_NOT_CONNECTED));
                guild.moveVoiceMember(guild.getMemberById(captain2.getID()), guild.getVoiceChannelById(vc2ID)).queue(null, new ErrorHandler().ignore(ErrorResponse.USER_NOT_CONNECTED));
            } catch (Exception ignored) {}

            this.remainingPlayers.remove(captain1);
            this.remainingPlayers.remove(captain2);
        }

        for (Player p : players) {
            guild.getTextChannelById(channelID).createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).queue();
            guild.getVoiceChannelById(vc1ID).createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).setAllow(Permission.VOICE_CONNECT).queue();
            guild.getVoiceChannelById(vc2ID).createPermissionOverride(guild.getMemberById(p.getID())).setAllow(Permission.VIEW_CHANNEL).setAllow(Permission.VOICE_CONNECT).queue();
        }

        for (Player p : remainingPlayers) {
            try {
                guild.moveVoiceMember(guild.getMemberById(p.getID()), guild.getVoiceChannelById(vc1ID)).queue(null, new ErrorHandler().ignore(ErrorResponse.USER_NOT_CONNECTED));
            } catch (Exception ignored) {}
        }

        GameCache.initializeGame(this);
        createGame(this);
    }

    public Game(int number) {
        this.number = number;
        this.guild = RBW.getGuild();

        this.team1 = new ArrayList<>();
        this.team2 = new ArrayList<>();
        this.eloGain = new HashMap<>();
        this.remainingPlayers = new ArrayList<>();
        this.players = new ArrayList<>();

        ResultSet resultSet = SQLite.queryData("SELECT * FROM games WHERE number=" + number + ";");

        try {
            this.state = GameState.valueOf(resultSet.getString(2).toUpperCase());
            this.casual = Boolean.parseBoolean(resultSet.getString(3));
            this.map = MapCache.getMap(resultSet.getString(4));
            this.channelID = resultSet.getString(5);
            this.vc1ID = resultSet.getString(6);
            this.vc2ID = resultSet.getString(7);
            this.queue = QueueCache.getQueue(resultSet.getString(8));

            if (state != GameState.STARTING) {
                if (state != GameState.SCORED) {
                    for (int i = 0; i < queue.getPlayersEachTeam(); i++) {
                        team1.add(PlayerCache.getPlayer(resultSet.getString(9).split(",")[i]));
                        team2.add(PlayerCache.getPlayer(resultSet.getString(10).split(",")[i]));
                        players.add(team1.get(i));
                        players.add(team2.get(i));
                    }
                }
                else {
                    for (int i = 0; i < queue.getPlayersEachTeam(); i++) {
                        team1.add(PlayerCache.getPlayer(resultSet.getString(9).split(",")[i].split("=")[0]));
                        eloGain.put(team1.get(i), Integer.parseInt(resultSet.getString(9).split(",")[i].split("=")[1]));

                        team2.add(PlayerCache.getPlayer(resultSet.getString(10).split(",")[i].split("=")[0]));
                        eloGain.put(team2.get(i), Integer.parseInt(resultSet.getString(10).split(",")[i].split("=")[1]));

                        players.add(team1.get(i));
                        players.add(team2.get(i));
                    }
                }
            }

            if (state == GameState.SCORED) {
                if (!(resultSet.getString(11) == null)) {
                    this.mvp = PlayerCache.getPlayer(resultSet.getString(11));
                }
                this.scoredBy = guild.getMemberById(resultSet.getString(12));
            }

            if (state == GameState.VOIDED) {
                this.scoredBy = guild.getMemberById(resultSet.getString(12));
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }

        GameCache.initializeGame(this);
    }

    public void pickTeams() {

        // bad way of doing a map check but oh well im lazy
        if (this.map == null) {
            Embed embed = new Embed(EmbedType.ERROR, "No Maps Available", "There are currently no maps available, please try queueing again later (`=maps`)", 1);

            String mentions = "";
            for (Player p : players) {
                mentions += guild.getMemberById(p.getID()).getAsMention();
            }

            guild.getTextChannelById(channelID).sendMessage(mentions).setEmbeds(embed.build()).queue();

            closeChannel(300);
            setState(GameState.VOIDED);
            setScoredBy(RBW.guild.getMemberById(RBW.jda.getSelfUser().getId()));

            return;
        }

        playersInParties = new HashMap<>();

        if (queue.getPickingMode() == PickingMode.AUTOMATIC) {
            if (PartyCache.getParty(captain1) != null) {
                playersInParties.put(captain1, PartyCache.getParty(captain1).getMembers().size());
            }

            if (PartyCache.getParty(captain2) != null) {
                playersInParties.put(captain2, PartyCache.getParty(captain2).getMembers().size());
            }

            // check for any parties
            List<Party> parties = new ArrayList<>();
            for (Player p : remainingPlayers) {
                if (PartyCache.getParty(p) != null) {
                    playersInParties.put(p, PartyCache.getParty(p).getMembers().size());
                    if (!parties.contains(PartyCache.getParty(p))) {
                        parties.add(PartyCache.getParty(p));
                    }
                }
            }

            if (parties.size() > 0) {
                for (Party p : parties) {
                    if (team1.size() < team2.size()) {
                        if (queue.getPlayersEachTeam() - team1.size() >= p.getMembers().size()) {
                            team1.addAll(p.getMembers());
                        }

                    }
                    else {
                        if (queue.getPlayersEachTeam() - team2.size() >= p.getMembers().size()) {
                            team2.addAll(p.getMembers());
                        }
                    }

                    remainingPlayers.removeAll(p.getMembers());
                }
            }

            Collections.shuffle(remainingPlayers);
            for (Player p : remainingPlayers) {
                if (queue.getPlayersEachTeam() - team1.size() > 0) {
                    team1.add(p);
                }
                else {
                    team2.add(p);
                }
            }
            remainingPlayers.clear();

            start();
        }
        else {
            sendGameMsg();
        }
    }

    public void start() {
        state = GameState.PLAYING;

        sendGameMsg();

        for (Player p : team2) {
            try {
                guild.moveVoiceMember(guild.getMemberById(p.getID()), guild.getVoiceChannelById(vc2ID)).queue(null, new ErrorHandler().ignore(ErrorResponse.USER_NOT_CONNECTED));
            } catch (Exception ignored) {}
        }
        if (casual) {
            closeChannel(1800);
        }

        warpToMap(5);

        updateGame(this);
    }

    public void sendGameMsg() {

        Embed embed = new Embed(EmbedType.DEFAULT, "Game `#" + number + "`", "", 1);

        if (queue.getPickingMode() == PickingMode.CAPTAINS) {
            embed.setDescription(guild.getMemberById(currentCaptain.getID()).getAsMention() + "'s turn to `=pick`");
        }

        String team1 = "";
        for (Player p : this.team1) {
            team1 += "• <@!" + p.getID() + ">\n";
        }
        embed.addField("Team 1", team1, true);

        String team2 = "";
        for (Player p : this.team2) {
            team2 += "• <@!" + p.getID() + ">\n";
        }
        embed.addField("Team 2", team2, true);

        if (remainingPlayers.size() != 0) {
            String remaining = "";
            for (Player p : remainingPlayers) {
                remaining += "• <@!" + p.getID() + ">\n";
            }

            embed.addField("Remaining", remaining, false);
        }

        embed.addField("Randomly Picked Map", "**" + map.getName() + "** — `Height: " + map.getHeight() + "` (" + map.getTeam1() + " vs " + map.getTeam2() + ")", false);

        if (remainingPlayers.size() == 0) {
            embed.setDescription("");
            embed.setTitle("Game `#" + number + "` has started!");

            guild.getTextChannelById(Config.getValue("games-announcing")).sendMessageEmbeds(embed.build()).queue();

            if (Config.getValue("party-invite-cmd") != null) {
                String party = Config.getValue("party-invite-cmd");
                for (Player p : players) {
                    party += " " + p.getIgn();
                }
                embed.addField("Party Invite Cmd", "`" + party + "`", false);
            }

            if (casual) {
                embed.setDescription("You queued a casual queue meaning this game will have no impact on players' stats");
            }

            embed.setDescription("do not forget to `=submit` after your game ends");
        }

        String mentions = "";
        for (Player p : players) {
            mentions += guild.getMemberById(p.getID()).getAsMention();
        }

        guild.getTextChannelById(channelID).sendMessage(mentions).setEmbeds(embed.build()).queue();
    }

    // bool - was the action successful or not
    public boolean pickPlayer(Player sender, Player picked) {

        if (state != GameState.STARTING) {
            return false;
        }

        if (sender != currentCaptain) {
            return false;
        }

        if (!remainingPlayers.contains(picked)) {
            return false;
        }

        if (sender == picked) {
            return false;
        }

        getPlayerTeam(sender).add(picked);
        remainingPlayers.remove(picked);

        currentCaptain = getOppTeam(PlayerCache.getPlayer(sender.getID())).get(0);

        sendGameMsg();

        if (remainingPlayers.size() == 1) {
            getOppTeam(sender).add(remainingPlayers.get(0));
            remainingPlayers.remove(remainingPlayers.get(0));

            start();
        }

        return true;
    }

    public List<Player> getPlayerTeam(Player player) {
        if (team1.contains(player)) {
            return team1;
        }

        if (team2.contains(player)) {
            return team2;
        }

        return null;
    }

    public List<Player> getOppTeam(Player player) {
        if (!team1.contains(player)) {
            return team1;
        }

        if (!team2.contains(player)) {
            return team2;
        }

        return null;
    }

    public void scoreGame(List<Player> winningTeam, List<Player> losingTeam, Player mvp, Member scoredBy) {

        eloGain = new HashMap<>();

        for (Player p : players) {
            eloGain.put(p, 0);

            int difference;
            int elo = p.getElo();


            double eloMultiplier = Double.parseDouble(Config.getValue("solo-multiplier"));

            if (playersInParties != null) {
                if (queue.getPickingMode() == PickingMode.AUTOMATIC) {
                    if (playersInParties.containsKey(p)) {
                        eloMultiplier = Double.parseDouble(Config.getValue("party-multiplier-" + playersInParties.get(p)));
                    }
                }
            }

            if (winningTeam.contains(p)) {
                p.win(queue.getEloMultiplier() * eloMultiplier);
            }
            else if (losingTeam.contains(p)) {
                p.lose(queue.getEloMultiplier() * eloMultiplier);
            }
            difference = p.getElo() - elo;
            eloGain.put(p, eloGain.get(p) + difference);
            p.fix();
        }

        if (mvp != null) {
            setMvp(mvp);
            mvp.setMvp(mvp.getMvp() + 1);
            mvp.setElo(mvp.getElo() + mvp.getRank().getMvpElo());
            eloGain.put(mvp, eloGain.get(mvp) + mvp.getRank().getMvpElo());
            mvp.fix();
        }

        setState(GameState.SCORED);

        setScoredBy(scoredBy);

        if (scoredBy != RBW.guild.getMemberById(RBW.jda.getSelfUser().getId())) {
            Player player = PlayerCache.getPlayer(scoredBy.getId());
            player.setScored(player.getScored() + 1);
        }

        SQLGameManager.updateEloGain(number);

        // EMBED

        Embed embed = new Embed(EmbedType.SUCCESS, "Game `#" + number + "` has been scored", "", 1);

        String team1 = "";
        for (Player p : this.team1) {
            team1 += "• <@" + p.getID() + "> `(+)`**" + eloGain.get(p) + "** `" + (p.getElo() - eloGain.get(p)) + "` > `" + p.getElo() + "`\n";
        }

        String team2 = "";
        for (Player p : this.team2) {
            team2 += "• <@" + p.getID() + "> `(+)`**" + eloGain.get(p) + "** `" + (p.getElo() - eloGain.get(p)) + "` > `" + p.getElo() + "`\n";
        }

        embed.addField("Team 1:", team1, false);
        embed.addField("Team 2:", team2, false);

        if (mvp != null) {
            embed.addField("MVP", "<@" + mvp.getID() + ">", false);
        }

        embed.addField("Scored by", scoredBy.getAsMention(), false);

        if (!Objects.equals(Config.getValue("scored-announcing"), null)) {
            guild.getTextChannelById(Config.getValue("scored-announcing")).sendMessageEmbeds(embed.build()).queue();
        }

        if (guild.getTextChannelById(channelID) != null) {
            guild.getTextChannelById(channelID).sendMessageEmbeds(embed.build()).queue();
        }

        // EMBED END

        closeChannel(Integer.parseInt(Config.getValue("game-deleting-time")));
    }

    public void undo() {
        setState(GameState.SUBMITTED);

        for (Player p : eloGain.keySet()) {
            if (eloGain.get(p) > 0) {
                p.setWins(p.getWins() - 1);
            }
            else {
                p.setLosses(p.getLosses() - 1);
            }

            p.setElo(p.getElo() - eloGain.get(p));
            if (p.getElo() < 0) {
                p.setElo(0);
            }

            p.fix();
        }

        Player player = PlayerCache.getPlayer(scoredBy.getId());
        player.setScored(player.getScored() - 1);

        if (mvp != null) {
            mvp.setMvp(mvp.getMvp() - 1);
        }

        SQLGameManager.removeEloGain(number);
    }

    public void closeChannel(int timeSeconds) {
        if (guild.getTextChannelById(channelID) != null) {
            Embed embed = new Embed(EmbedType.DEFAULT, "", "Game channel deleting in `" + timeSeconds + "` seconds / `" + timeSeconds / 60 + "` minutes", 1);
            guild.getTextChannelById(channelID).sendMessageEmbeds(embed.build()).queue();
        }

        if (closingTask != null) {
            closingTask.cancel();
        }

        closingTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    if (guild.getTextChannelById(channelID) != null) {
                        guild.getTextChannelById(channelID).delete().queue();
                    }
                    if (guild.getVoiceChannelById(vc1ID) != null) {
                        guild.getVoiceChannelById(vc1ID).delete().queue();
                    }
                    if (guild.getVoiceChannelById(vc2ID) != null) {
                        guild.getVoiceChannelById(vc2ID).delete().queue();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        new Timer().schedule(closingTask, timeSeconds * 1000L);
    }

    public void warpToMap(int triesMax) {
        Bukkit.getScheduler().runTask(RBW.getInstance(), () -> {
            IArena arena =  RBW.bedwarsAPI.getArenaUtil().getArenaByName(this.map.getName());

            new BukkitRunnable() {
                int tries = 0;

                @Override
                public void run() {
                    tries++;

                    try {
                        for (Player p : team1) {
                            if (arena.getPlayers().contains(Bukkit.getPlayer(p.getIgn()))) {
                                continue;
                            }

                            // try to warp
                            if (!arena.addPlayer(Bukkit.getPlayer(p.getIgn()), true)) {
                                Embed embed = new Embed(EmbedType.ERROR, "Failed To Warp", "<@" + p.getID() + "> please get online on `" + Config.getValue("server-ip") + "`\nRetrying to warp in `30` seconds `[Tries: " + tries + "/" + triesMax +"]`", 1);

                                guild.getTextChannelById(channelID).sendMessage("<@" + p.getID() + ">").setEmbeds(embed.build()).queue();
                            }

                            // set team
                            arena.getTeams().get(0).addPlayers(Bukkit.getPlayer(p.getIgn()));
                        }

                        for (Player p : team2) {
                            if (arena.getPlayers().contains(Bukkit.getPlayer(p.getIgn()))) {
                                continue;
                            }

                            // try to warp
                            if (!arena.addPlayer(Bukkit.getPlayer(p.getIgn()), true)) {
                                Embed embed = new Embed(EmbedType.ERROR, "Failed To Warp", "<@" + p.getID() + "> please get online on `" + Config.getValue("server-ip") + "`\nRetrying to warp in `30` seconds `[Tries: " + tries + "/" + triesMax +"]`", 1);

                                guild.getTextChannelById(channelID).sendMessage("<@" + p.getID() + ">").setEmbeds(embed.build()).queue();
                            }

                            // set team
                            arena.getTeams().get(1).addPlayers(Bukkit.getPlayer(p.getIgn()));
                        }

                        if (arena.getPlayers().size() == team1.size() + team2.size()) {
                            cancel();

                            Embed embed = new Embed(EmbedType.SUCCESS, "Warped To Map", "Successfully warped everyone to map `" + arena.getArenaName() + "`", 1);
                            guild.getTextChannelById(channelID).sendMessageEmbeds(embed.build()).queue();
                            return;
                        }

                        if (tries == triesMax) {
                            Embed embed = new Embed(EmbedType.ERROR, "Failed To Warp", "All tries `[" + triesMax + "/" + triesMax + "]` used\nPlease retry using `=retry` or `=void` the game", 1);
                            guild.getTextChannelById(channelID).sendMessageEmbeds(embed.build()).queue();
                            cancel();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        cancel();
                    }
                }
            }.runTaskTimer(RBW.getInstance(), 20L, 20L * 30);
        });
    }

    public static void createGame(Game g) {
        SQLGameManager.createGame(g);
    }

    public static void updateGame(Game g) {
        SQLGameManager.updateGame(g);
    }

    public HashMap<Player, Integer> getEloGain() {
        return eloGain;
    }

    public int getNumber() {
        return number;
    }

    public String getChannelID() {
        return channelID;
    }

    public Guild getGuild() {
        return guild;
    }

    public void setGuild(Guild guild) {
        this.guild = guild;
    }

    public String getVC1ID() {
        return vc1ID;
    }

    public String getVC2ID() {
        return vc2ID;
    }

    public Queue getQueue() {
        return queue;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Player> getTeam1() {
        return team1;
    }

    public List<Player> getTeam2() {
        return team2;
    }

    public List<Player> getRemainingPlayers() {
        return remainingPlayers;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
        SQLGameManager.updateState(number);
    }

    public boolean isCasual() {
        return casual;
    }

    public GameMap getMap() {
        return map;
    }

    public Player getMvp() {
        return mvp;
    }

    public void setMvp(Player mvp) {
        this.mvp = mvp;
        SQLGameManager.updateMvp(number);
    }

    public Member getScoredBy() {
        return scoredBy;
    }

    public void setScoredBy(Member scoredBy) {
        this.scoredBy = scoredBy;
        SQLGameManager.updateScoredBy(number);
    }
}
