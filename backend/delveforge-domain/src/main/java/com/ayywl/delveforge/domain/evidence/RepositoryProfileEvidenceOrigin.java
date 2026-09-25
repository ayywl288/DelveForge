package com.ayywl.delveforge.domain.evidence;

import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

/**
 * 该 Evidence 来自某一份 Repository Profile。
 *
 * <p>Repository Profile 是不可改写的分析快照，它自己就绑定了一个确定 Software Asset 与
 * {@code analyzedRevision}（INV-D03、INV-D04），因此这里只需要记它的身份——
 * 资产与 revision 通过该 Profile 即可追溯。
 *
 * @param repositoryProfileId 该依据所属的 Repository Profile，不得为 {@code null}
 */
public record RepositoryProfileEvidenceOrigin(RepositoryProfileId repositoryProfileId)
        implements EvidenceOrigin {

    public RepositoryProfileEvidenceOrigin {
        if (repositoryProfileId == null) {
            throw new IllegalArgumentException(
                    "Repository Profile Evidence Origin 必须指定 repositoryProfileId");
        }
    }
}
