/**
 * Profile Management：User Profile 的创建、读取与结构化更新。
 *
 * <p>这三个 Use Case 都只转发给 {@code UserProfile} Aggregate 的既有领域行为，
 * 不判断内容是否合法、不计算 revision。更新发生在隔离的候选副本上，
 * 因此被 Aggregate 拒绝时不会留下半更新状态。
 */
package com.ayywl.delveforge.application.userdiscovery.profile;
