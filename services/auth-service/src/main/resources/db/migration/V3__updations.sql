-- Ensure columns exist idempotently
ALTER TABLE auth_signup_transactions ADD COLUMN IF NOT EXISTS provider_subject VARCHAR(255);
ALTER TABLE auth_signup_transactions ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES auth_users(id) ON DELETE SET NULL;

-- Performance indexes for phone lookups and verification queries
CREATE INDEX IF NOT EXISTS idx_auth_user_phones_user_id ON auth_user_phones(user_id);
CREATE INDEX IF NOT EXISTS idx_challenges_user_lookup ON auth_verification_challenges(user_id, channel, purpose);
CREATE INDEX IF NOT EXISTS idx_signup_txn_phone ON auth_signup_transactions(phone);
