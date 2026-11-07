package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.user.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.strategy.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "ADMIN: emails sent to users (sign-up, approval, rejection, payment)")
public class NotificationController {

    private final EmailNotificationRepository repo;
    private final EmailDispatcher dispatcher;
    private final UserService users;
    private final EmailService emails;
    private final SetupStatus setupStatus;

    public NotificationController(EmailNotificationRepository repo, EmailDispatcher dispatcher, UserService users,
                                  EmailService emails, SetupStatus setupStatus) {
        this.repo = repo; this.dispatcher = dispatcher; this.users = users; this.emails = emails; this.setupStatus = setupStatus;
    }

    @GetMapping("/email/status")
    @Operation(summary = "ADMIN: whether SMTP is configured (otherwise emails are only logged)")
    public Map<String, Object> status() {
        users.requireAdmin();
        return Map.of("smtpConfigured", dispatcher.smtpConfigured());
    }

    @GetMapping("/setup")
    @Operation(summary = "ADMIN: whether email (SMTP) and Razorpay payments are set up on this server, and what is missing")
    public Map<String, Object> setup() {
        users.requireAdmin();
        return setupStatus.status();
    }

    @PostMapping("/email/test")
    @Operation(summary = "ADMIN: send a test email to your own address now and return the result (SENT, LOGGED or FAILED with the error)")
    public EmailNotification test() {
        Long me = users.requireAdminId();
        AppUser admin = users.get(me);
        Long id = emails.test(admin);
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your account has no email address: add one under Users first");
        dispatcher.dispatch(id);
        return repo.findById(id).orElseThrow();
    }

    @GetMapping("/emails")
    @Operation(summary = "ADMIN: recent emails, newest first, optionally for one user")
    @Transactional(readOnly = true)
    public List<EmailNotification> list(@RequestParam(required = false) Long userId,
                                        @RequestParam(defaultValue = "100") int limit) {
        users.requireAdmin();
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return userId == null ? repo.findAllByOrderByCreatedAtDesc(page) : repo.findByUserIdOrderByCreatedAtDesc(userId, page);
    }

    @PostMapping("/emails/{id}/retry")
    @Operation(summary = "ADMIN: send a failed email again now")
    public EmailNotification retry(@PathVariable Long id) {
        users.requireAdmin();
        EmailNotification n = repo.findById(id).orElseThrow(() -> new NotFoundException("Email not found: " + id));
        n.requeue();
        repo.save(n);
        dispatcher.dispatch(id);
        return repo.findById(id).orElseThrow();
    }
}
