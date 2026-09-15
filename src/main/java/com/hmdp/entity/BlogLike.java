package com.hmdp.entity;

import lombok.Data;

@Data
public class BlogLike {
    private Long blogId;
    private Long userId;
    private Long likedAt;
}
