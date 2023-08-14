package cn.paper_card.player_qq_group_remark;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface PlayerQqGroupRemarkApi {
    record QqRemarkInfo(
            long qq,
            String remark
    ) {
    }

    interface QqBot {
        void sendAtMessage(long member, @NotNull String message);

        void sendNormalMessage(@NotNull String message);

        void tryModifyQqRemark(long member, @NotNull String message) throws Exception;
    }


    // 获取主群里成员的群昵称
    @Nullable String getRemark(long qq) throws Exception;

    boolean addOrUpdateRemarkByQq(long qq, @Nullable String remark) throws Exception;

    // 查询以指定内容开头的群成员的QQ号码
    @SuppressWarnings("unused")
    @NotNull List<Long> queryQqByRemarkStartWith(@NotNull String prefix) throws Exception;

    // 保证QQ号码有效再调用
    @SuppressWarnings("unused")
    void onPreLoginCheck(@NotNull AsyncPlayerPreLoginEvent event, long qq, @Nullable QqBot qqBot);

    @SuppressWarnings("unused")
    boolean updateRemarkByGroupMessage(long qq, @NotNull String remark);

}
