/**
 * Repository Profile 的查询。
 *
 * <p>只读：Profile 是一次分析的快照，没有修改入口，也没有状态机（DOMAIN_MODEL.md §6）。
 * 这里只提供按标识读取，不提供按资产或按 revision 的查询——当前没有这样的消费者。
 */
package com.ayywl.delveforge.application.repositoryanalysis.profile;
