/**
 * Repository Profile 领域模型：某个 Software Asset 在确定 revision 下的一次分析快照。
 *
 * <p>Repository Profile 是引用 {@code assetId} 的独立 Aggregate（DOMAIN_MODEL.md §11.5），
 * 不与 Software Asset 放在同一个包中：它不拥有 Software Asset，也不被 Software Asset 拥有，
 * 两者只通过身份建立关系。
 *
 * <p>本 Aggregate 没有状态机（§6）：它是一次分析结果，不是一个会随源 Repository 演进的
 * 领域对象。源 Repository 发生变化后需要重新分析时，形成新的 Repository Profile。
 */
package com.ayywl.delveforge.domain.repositoryprofile;
