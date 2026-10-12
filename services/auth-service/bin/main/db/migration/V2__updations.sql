ALTER TABLE auth_signup_transactions ADD COLUMN provider_subject VARCHAR(255);
ALTER TABLE auth_signup_transactions ADD COLUMN user_id UUID REFERENCES auth_users(id) ON DELETE SET NULL;
