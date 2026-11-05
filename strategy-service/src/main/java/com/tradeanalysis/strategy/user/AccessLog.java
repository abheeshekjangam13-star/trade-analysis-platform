package com.tradeanalysis.strategy.user;

import jakarta.persistence.*;

import java.time.Instant;

/** One API request as recorded by the api-gateway (read-only here). */
@Entity
@Table(name = "access_log")
public class AccessLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)   private String username;
    @Column(nullable = false, length = 10)   private String method;
    @Column(nullable = false, length = 1024) private String path;
    @Column(length = 2048) private String query;
    private Integer statusCode;
    private Long durationMs;
    @Column(length = 64)   private String clientIp;
    @Column(length = 512)  private String userAgent;
    @Column(nullable = false) private Instant createdAt;

    protected AccessLog() {}

    public Long getId()          { return id; }
    public String getUsername()  { return username; }
    public String getMethod()    { return method; }
    public String getPath()      { return path; }
    public String getQuery()     { return query; }
    public Integer getStatusCode(){ return statusCode; }
    public Long getDurationMs()  { return durationMs; }
    public String getClientIp()  { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt(){ return createdAt; }
}
