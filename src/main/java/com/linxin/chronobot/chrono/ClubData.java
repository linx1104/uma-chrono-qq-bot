package com.linxin.chronobot.chrono;

import java.util.List;

/**
 * 社团粉丝数据
 *
 * @param fanCount       当日粉丝数
 * @param dailyAverage   当月平均每日粉丝
 * @param monthlyAverage 当月总粉丝
 * @param dailyDiff      当日增量
 * @param rank           排名
 * @param updatedAt      数据更新时间 (站点原始格式)
 * @param memberNum      成员数
 * @param members        成员粉丝数据 (club_friend_profile, 含名字与粉丝字段)
 */
public record ClubData(
        String queryName,
        String name,
        long circleId,
        long fanCount,
        long dailyAverage,
        long monthlyAverage,
        long dailyDiff,
        int rank,
        String updatedAt,
        int memberNum,
        List<MemberData> members
) {

    /**
     * 单个成员粉丝数据
     *
     * @param viewerId      成员 ID
     * @param name          当前名字
     * @param names         历史/曾用名 (用于模糊匹配)
     * @param fanCount      当日粉丝数
     * @param dailyAverage  当月平均每日粉丝
     * @param monthlyAverage 当月总粉丝
     * @param dailyDiff     当日增量
     * @param monthlyDiff   当月增量
     * @param lastMonth     上月总粉丝
     * @param joinTime      加入社团时间
     * @param lastLoginTime 最近登录时间
     * @param membership    身份 (1 成员 / 2 副社 / 3 社长)
     * @param updatedAt     数据更新时间
     */
    public record MemberData(
            long viewerId,
            String name,
            List<String> names,
            long fanCount,
            long dailyAverage,
            long monthlyAverage,
            long dailyDiff,
            long monthlyDiff,
            long lastMonth,
            String joinTime,
            String lastLoginTime,
            int membership,
            String updatedAt
    ) {
    }
}
