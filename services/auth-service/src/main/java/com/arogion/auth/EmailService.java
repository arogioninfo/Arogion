package com.arogion.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends verification emails via SMTP using Spring's JavaMailSender.
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    /**
     * Sends a verification OTP email to the specified recipient.
     *
     * @param to  recipient email address
     * @param otp the plaintext OTP to include in the email body
     */
    public void sendOtp(String to, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject("Your Arogion Verification Code");
        message.setText(
                "Hello,\n\n" +
                "Your Arogion verification code is: " + otp + "\n\n" +
                "This code expires in 5 minutes.\n\n" +
                "If you didn't request this code, please ignore this email.\n\n" +
                "— Arogion Team"
        );
        mailSender.send(message);
    }
}
