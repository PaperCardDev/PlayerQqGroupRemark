package cn.paper_card.player_qq_group_remark;

import cn.paper_card.database.api.DatabaseApi;
import cn.paper_card.database.api.Util;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;


@SuppressWarnings("unused")
public final class PlayerQqGroupRemark extends JavaPlugin implements PlayerQqGroupRemarkApi {

    private final @NotNull HashMap<Long, String> cache; // 缓存
    private QqRemarkTable table = null;
    private Connection connection = null;

    private final @NotNull Object connectionLock = new Object();

    private final @NotNull SessionManager sessionManager;

    public PlayerQqGroupRemark() {
        this.cache = new HashMap<>();
        this.sessionManager = new SessionManager();
    }


    @Override
    public void onEnable() {
        final DatabaseApi api = this.getServer().getServicesManager().load(DatabaseApi.class);
        if (api == null) throw new RuntimeException("无法连接到" + DatabaseApi.class.getSimpleName());

        try {
            this.connection = api.getLocalSQLite().connectUnimportant();
        } catch (SQLException e) {
            throw new RuntimeException("无法连接到数据库！", e);
        }
    }

    @Override
    public void onDisable() {
        synchronized (this.connectionLock) {
            if (this.table != null) {
                try {
                    this.table.close();
                } catch (SQLException e) {
                    this.getLogger().severe(e.toString());
                    e.printStackTrace();
                }
                this.table = null;
            }

            if (this.connection != null) {
                try {
                    this.connection.close();
                } catch (SQLException e) {
                    this.getLogger().severe(e.toString());
                    e.printStackTrace();
                }
                this.connection = null;
            }
        }
    }


    private @NotNull QqRemarkTable getTable() throws Exception {
        if (this.table == null) {
            this.table = new QqRemarkTable(this.connection);
        }
        return this.table;
    }

    @Override
    public @Nullable String getRemark(long qq) throws Exception {
        synchronized (this.cache) {
            final String s = this.cache.get(qq);
            if (s != null) return s;

            synchronized (this.connectionLock) {
                final QqRemarkTable t = this.getTable();
                final List<QqRemarkInfo> list = t.queryByQq(qq);
                final int size = list.size();
                if (size == 0) return null;
                final QqRemarkInfo info = list.get(0); // 应该不为空
                final String remark = info.remark();
                this.cache.put(qq, remark); // 缓冲起来，下次不用查数据库了
                return remark;
            }
        }
    }

    @Override
    public boolean addOrUpdateRemarkByQq(long qq, @Nullable String remark) throws Exception {

        // 放到缓存
        synchronized (this.cache) {
            this.cache.put(qq, remark);
        }

        synchronized (this.connectionLock) {
            final QqRemarkTable t = this.getTable();
            final int i = t.updateByQq(qq, remark);
            if (i == 1) return false;
            if (i == 0) {
                final int insert = t.insert(qq, remark);
                if (insert != 1) throw new Exception("插入了%d条（应该是1条）数据！".formatted(insert));
                return true;
            }

            throw new Exception("根据一个QQ[%d]更新了%d条（应该是0或1条）数据！".formatted(qq, i));
        }
    }

    @Override
    public @NotNull List<Long> queryQqByRemarkStartWith(@NotNull String prefix) throws Exception {

        final List<Long> list = new LinkedList<>();

        // 从数据库中查
        synchronized (this.connectionLock) {
            final QqRemarkTable t = this.getTable();
            final List<QqRemarkInfo> list1 = t.queryRemarkLike(prefix + '%');

            for (final QqRemarkInfo info : list1) {
                if (info == null) continue;
                if (info.qq() == 0) continue;
                list.add(info.qq());
            }
        }

        return list;
    }

    @Override
    public void onPreLoginCheck(@NotNull AsyncPlayerPreLoginEvent event, long qq, @Nullable QqBot qqBot) {

        if (qqBot == null) {
            this.getLogger().info("由于QQ机器人无法访问主群，因此跳过群昵称检查");
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
            return;
        }

        // 验证玩家的QQ群昵称是否以游戏ID开头
        final String remark;

        try {
            remark = this.getRemark(qq);
        } catch (Exception e) {
            event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            event.kickMessage(Component.text(e.toString()).color(NamedTextColor.RED));
            return;
        }

        if (remark == null || !remark.startsWith(event.getName())) { // 无法获取群昵称 不正确的群昵称
            try {
                // 尝试为他设置群昵称
                qqBot.tryModifyQqRemark(qq, event.getName());

                qqBot.sendAtMessage(qq, "已自动设置你的群昵称为你的游戏名：%s".formatted(event.getName()));

                this.addOrUpdateRemarkByQq(qq, event.getName());

                this.getLogger().info("为玩家[%s]设置了群昵称".formatted(event.getName()));

                event.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);

                return;

            } catch (Exception e) {
                e.printStackTrace();

                this.getLogger().info("无法为玩家[%s]设置群昵称".formatted(event.getName()));

                this.kickQqRemark(event, qq, remark);

                this.notifyQqRemark(qqBot, event, qq);


                return;
            }
        }

        this.getLogger().info("玩家QQ群昵称[" + remark + "]验证通过！");
        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
    }


    // 在群里发送消息让玩家改群昵称
    private void notifyQqRemark(@NotNull QqBot qqBot, @NotNull AsyncPlayerPreLoginEvent event, long qq) {

        // 如果之前已经发送过了，就不再发送了
        final Session session = this.sessionManager.getSession(event.getUniqueId());
        final long lastKickByRemarkTime = session.getLastKickByRemarkTime();
        final long curTime = System.currentTimeMillis();
        session.setLastKickByRemarkTime(curTime);
        if (curTime - lastKickByRemarkTime > 2 * 60L * 1000L) { // 两分钟
            // todo: 如果他不在群里了呢？
            qqBot.sendAtMessage(qq, "请修改您的群昵称，它应该以您的游戏名开头，" +
                    "修改后请在群里发送任意消息以更新信息，您的游戏名如下，请直接复制以防止出错：");
            qqBot.sendNormalMessage(event.getName());
        }
    }

    // 群昵称不正确而拒绝连接
    private void kickQqRemark(@NotNull AsyncPlayerPreLoginEvent event, long qq, @Nullable String remark) {


        final TextComponent msg = Component.text()

                .append(Component.text("[QQ群昵称不正确]"))
                .append(Component.newline())

                .append(Component.text("群昵称应该以您的游戏ID["))
                .append(Component.text(event.getName()).color(NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("]开头"))
                .append(Component.newline())

                .append(Component.text("修改后请在群里发送任意消息以更新信息"))
                .append(Component.newline())

                .append(Component.text("您的群昵称："))
                .append(Component.text(remark != null ? remark : "无法获取").color(NamedTextColor.AQUA))
                .append(Component.text(" ["))
                .append(Component.text(qq).color(NamedTextColor.GRAY))
                .append(Component.text("]"))

                .build();

        event.kickMessage(msg);
        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST);

    }

    @Override
    public boolean updateRemarkByGroupMessage(long qq, @NotNull String newRemark) {
        final String oldRemark;
        synchronized (this.cache) {
            oldRemark = this.cache.get(qq);

            // 没有变化
            if (oldRemark != null && oldRemark.equals(newRemark)) return false;

            this.getLogger().info("群昵称变化：{new: %s, old: %s}".formatted(newRemark, oldRemark));
        }

        boolean add;
        try {
            add = this.addOrUpdateRemarkByQq(qq, newRemark);
        } catch (Exception e) {
            this.getLogger().severe(e.toString());
            e.printStackTrace();
            return false;
        }

        if (add) {
            this.getLogger().info("录入了群昵称: %s".formatted(newRemark));
        }

        return true;
    }

    private static class Session {

        private long lastKickByRemarkTime = -1;

        private long getLastKickByRemarkTime() {
            return this.lastKickByRemarkTime;
        }

        private void setLastKickByRemarkTime(long time) {
            this.lastKickByRemarkTime = time;
        }
    }

    private static class SessionManager {

        private final @NotNull HashMap<UUID, Session> map;

        private SessionManager() {
            this.map = new HashMap<>();
        }

        @NotNull Session getSession(@NotNull UUID uuid) {
            synchronized (this.map) {
                Session session = this.map.get(uuid);
                if (session != null) return session;
                session = new Session();
                this.map.put(uuid, session);
                return session;
            }
        }
    }

    private static class QqRemarkTable {
        private final static String NAME = "qq_group_remark";

        private final PreparedStatement statementInsert;
        private final PreparedStatement statementQueryByQq;
        private final PreparedStatement statementUpdateByQq;

        private final PreparedStatement statementQueryByRemarkLike;

        QqRemarkTable(@NotNull Connection connection) throws SQLException {
            this.create(connection);

            try {
                this.statementInsert = connection.prepareStatement
                        ("INSERT INTO " + NAME + " (qq, remark) VALUES (?, ?)");

                this.statementQueryByQq = connection.prepareStatement
                        ("SELECT qq, remark FROM " + NAME + " WHERE qq=?");

                this.statementUpdateByQq = connection.prepareStatement
                        ("UPDATE " + NAME + " SET remark=? WHERE qq=?");

                this.statementQueryByRemarkLike = connection.prepareStatement
                        ("SELECT qq, remark FROM " + NAME + " WHERE remark LIKE ?");

            } catch (SQLException e) {
                try {
                    this.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }

        private void create(@NotNull Connection connection) throws SQLException {
            final String sql = "CREATE TABLE IF NOT EXISTS " + NAME + " (qq INTEGER PRIMARY KEY, remark CHAR(48))";
            Util.executeSQL(connection, sql);
        }

        private void close() throws SQLException {
            Util.closeAllStatements(this.getClass(), this);
        }

        private int insert(long qq, @Nullable String remark) throws SQLException {
            final PreparedStatement ps = this.statementInsert;
            ps.setLong(1, qq);
            ps.setString(2, remark);
            return ps.executeUpdate();
        }

        private @NotNull List<QqRemarkInfo> parse(@NotNull ResultSet resultSet) throws SQLException {
            final LinkedList<QqRemarkInfo> list = new LinkedList<>();
            try {
                while (resultSet.next()) {
                    final long qq = resultSet.getLong(1);
                    final String remark = resultSet.getString(2);
                    list.add(new QqRemarkInfo(qq, remark));
                }
            } catch (SQLException e) {
                try {
                    resultSet.close();
                } catch (SQLException ignored) {
                }
                throw e;
            }
            resultSet.close();
            return list;
        }

        @NotNull List<QqRemarkInfo> queryByQq(long qq) throws SQLException {
            final PreparedStatement ps = this.statementQueryByQq;
            ps.setLong(1, qq);
            final ResultSet resultSet = ps.executeQuery();
            return this.parse(resultSet);
        }

        private int updateByQq(long qq, @Nullable String remark) throws SQLException {
            final PreparedStatement ps = this.statementUpdateByQq;
            ps.setString(1, remark);
            ps.setLong(2, qq);
            return ps.executeUpdate();
        }

        private @NotNull List<QqRemarkInfo> queryRemarkLike(@NotNull String pattern) throws SQLException {
            final PreparedStatement ps = this.statementQueryByRemarkLike;
            ps.setString(1, pattern);
            final ResultSet resultSet = ps.executeQuery();
            return this.parse(resultSet);
        }
    }
}
