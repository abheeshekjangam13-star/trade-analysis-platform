package com.tradeanalysis.strategy.user;

import jakarta.persistence.*;

@Entity
@Table(name = "app_role")
public class AppRole {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String name;

    private String description;

    protected AppRole() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
