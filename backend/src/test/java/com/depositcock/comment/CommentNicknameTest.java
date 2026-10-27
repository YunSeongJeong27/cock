package com.depositcock.comment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommentNicknameTest {

    /** 닉네임에 붙은 동·호수를 뗀다. 닉네임이 동·호수뿐이면 닉네임은 그대로 (2026-10-26) */
    @ParameterizedTest(name = "{0} → {1} / {2}")
    @CsvSource(delimiter = '|', value = {
            "꼭지맘 217-1505|꼭지맘|217-1505",
            "꼭지맘217-1505|꼭지맘|217-1505",
            "캐파101동|캐파|101동",
            "딸기 101동 1203호|딸기|101동 1203호",
            "귤맘(1203호)|귤맘|1203호",
            "205-1205|205-1205|205-1205",
            "망고맘|망고맘|",
            "망고맘2|망고맘2|",
            "쏠|쏠|",
    })
    void split(String author, String nickname, String unit) {
        CommentNickname split = CommentNickname.split(author);
        assertThat(split.nickname()).as(author).isEqualTo(nickname);
        assertThat(split.unitAddress()).as(author).isEqualTo(unit);
    }
}
