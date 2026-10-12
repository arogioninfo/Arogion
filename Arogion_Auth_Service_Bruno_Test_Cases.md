# Arogion Authentication Service --- Manual Bruno Test Case Reference

## Purpose

This document contains the authentication-service test cases, expected
results, database verification guidance, and execution-recording fields
for manual testing with Bruno. Use it as the test reference while
working through the Arogion authentication service.

**Execution mode:** Manual. The tester sends each request in Bruno,
checks the response, verifies database state where applicable, and
records the result in the test tracker. This document does not authorize
automatic execution or database changes.

## Signup flows under test

### Google OAuth

Google sign-in → backend validates the Google ID token → phone
verification → password setup if required by the approved product policy
→ account activation → Arogion credentials.

### Email signup

Email verification → phone verification → password setup → account
activation → Arogion credentials.

### Phone signup

Phone verification → account activation, if the approved product policy
permits phone-only signup without a password.

The service must enforce verification order on the backend. Pending
accounts must not gain normal protected-API access before all required
steps are complete.

## Database tables and responsibilities

Use these team-defined table names and responsibilities. Confirm exact
columns, data types, foreign keys, indexes, and constraints from the
repository's migrations/entities before running queries. The table list
alone does not define the complete SQL schema.

  -----------------------------------------------------------------------
  Table                               Responsibility and expected
                                      constraints
  ----------------------------------- -----------------------------------
  `auth_users`                        Core identity, nullable normalized
                                      email, `email_verified_at`, account
                                      status, timestamps. Partial unique
                                      index on normalized email when not
                                      null.

  `auth_credentials`                  Optional password credential;
                                      unique `user_id`; store password
                                      hash only.

  `auth_external_identities`          Provider and `provider_subject`;
                                      unique
                                      `(provider, provider_subject)`;
                                      provider email is metadata only.

  `auth_user_phones`                  Phone association, normalized
                                      phone, `verified_at`, primary flag;
                                      enforce the agreed uniqueness
                                      policy for verified numbers.

  `auth_signup_transactions`          Persist incomplete signup across
                                      requests/restarts; method, status,
                                      email/phone verification
                                      timestamps, expiry and state.

  `auth_verification_challenges`      Email/phone channel, purpose,
                                      challenge hash/protected
                                      representation, expiry, attempt
                                      count, `consumed_at`, transaction
                                      binding.

  `auth_sessions`                     Hashed refresh token, expiry,
                                      revocation, last-used timestamp;
                                      never store raw refresh tokens.

  `auth_application_access`           User-to-application access such as
                                      PATIENT, DOCTOR, HOSPITAL; unique
                                      user/application pair or documented
                                      multi-membership design.

  `organizations`                     Hospital/tenant identity and
                                      status; keep organization metadata
                                      separate from auth user.

  `organization_memberships`          User-to-hospital membership,
                                      status, invited/accepted
                                      timestamps; unique
                                      user/organization pair where
                                      applicable.

  `organization_role_assignments`     Hospital Admin and Hospital Staff
                                      assignments scoped to
                                      membership/organization; define
                                      role keys and constraints.

  `auth_audit_events`                 Security events and timestamps;
                                      never record passwords, OTPs, raw
                                      tokens, or unnecessary sensitive
                                      data.

  `email_outbox`                      Registration/security email events,
                                      delivery status, retry schedule and
                                      idempotency.

  `patient_profiles`                  Patient-domain profile linked to an
                                      auth user; created for patient
                                      onboarding, not for doctor/hospital
                                      data.
  -----------------------------------------------------------------------

## Database invariants relevant to the test cases

-   Repeated signup must not create duplicate canonical accounts.
-   Google identity uniqueness is based on the validated provider
    subject (`sub`), scoped by provider.
-   Email normalization and the partial unique index must prevent
    duplicate normalized emails where email is present.
-   Verified phone uniqueness must follow the team's agreed policy and
    be enforced in PostgreSQL.
-   A user has at most one password credential under the stated
    `auth_credentials.user_id` uniqueness rule.
-   Verification challenges are transaction-bound, expire, track
    attempts, and cannot be consumed repeatedly.
-   Refresh tokens are stored as hashes, with expiry/revocation
    metadata.
-   User/application access assignments and organization memberships
    follow the documented uniqueness rules.
-   Organization roles remain scoped to the correct
    membership/organization.
-   Audit events do not store raw secrets.
-   Email outbox retries follow the intended idempotency behavior.
-   A patient profile references an existing auth user and is only
    created for patient onboarding.

Do not assume exact column or index names beyond what the source
code/migrations show. Do not create or alter tables just to make a test
pass.

## Duplicate-account and identity-linking expectations

-   Repeating signup with the same Google `sub`, normalized email, or
    verified normalized phone must not create a duplicate account.
-   A matching Google email alone must not silently link an unlinked
    Google identity to an existing account.
-   Identity linking requires proof of control of the existing account
    and the new identity.
-   A collision should lead to safe sign-in/recovery or an explicit
    linking flow, not automatic account merging.
-   After successful linking, all linked methods resolve to the same
    canonical `auth_users` record.
-   Pending signup retries and concurrent requests must not create
    duplicate users or conflicting identity associations.


### Environment and secret handling

-   Use a local/test environment and dedicated test identities.
-   Determine the correct host-accessible base URL from application
    configuration and Docker port mappings. A Docker-internal service
    name may not be accessible from Bruno running on the host.
-   Never commit or share Google client secrets, passwords, OTPs, access
    tokens, ID tokens, refresh tokens, or Bruno token caches.
-   Use synthetic data only; never test with real patient information.
-   Do not run destructive SQL against shared, staging, or production
    databases.

## Token types

-   **Google access token:** used to call Google APIs for granted
    scopes.
-   **Google ID token:** OIDC identity token; the backend must validate
    its signature, issuer, audience, expiry, and nonce where applicable.
-   **Arogion access token:** issued by Arogion after its own
    authentication/signup rules are satisfied; used on protected Arogion
    APIs.

Do not treat a Google token as an Arogion token unless a specific
endpoint explicitly validates Google tokens.

## Test case inventory

All 161 test cases below are from the current Arogion authentication
test workbook. Keep the IDs unchanged so results can be matched with the
workbook. Priority affects execution order, not whether a test should be
accounted for.

  -------------------------------------------------------------------------------------------------------------------------------------
  Category      Test Case  Test Scenario           Preconditions / Test   Test Steps             Expected Result             Priority
                ID                                 Data                                                                      
  ------------- ---------- ----------------------- ---------------------- ---------------------- --------------------------- ----------
  Duplicate     DUP-01     Google then Email with  Active Google account; Attempt Email signup   No second user; safe        Critical
  Prevention               same email              email verified         with same normalized   login/recovery or explicit  
                                                                          email                  linking, never silent       
                                                                                                 linking                     

  Duplicate     DUP-02     Phone account then      Active phone account   Attempt Email signup   No second user; prove       Critical
  Prevention               Email with same phone   with verified phone    using that phone       existing-account control    
                                                                                                 before linking              

  Duplicate     DUP-03     Email account then      Existing email         Attempt Google signup  No automatic linking; safe  Critical
  Prevention               Google with same email  account; Google sub    with matching verified conflict or explicit        
                           but different sub       unlinked               email                  linking flow                

  Duplicate     DUP-04     Google account then     Google account has     Attempt phone signup   No second account; safe     High
  Prevention               Phone with same phone   verified phone         with same number       login/recovery or approved  
                                                                                                 linking                     

  Duplicate     DUP-05     Email account then      Email account has      Google signup then     No second account; require  High
  Prevention               Google with different   verified phone         verify same phone      secure linking/recovery     
                           email but same phone                                                                              

  Duplicate     DUP-06     Phone account then      Phone account exists;  Google signup and      No second account; require  High
  Prevention               Google with same phone  Google email differs   verify existing phone  secure linking/recovery     

  Duplicate     DUP-07     Repeat Google signup    Google sub linked to   Repeat Google          Resolve existing account;   Critical
  Prevention               with same sub           active account         authentication         no duplicate user or        
                                                                                                 identity                    

  Duplicate     DUP-08     Repeat Email signup     Account uses           Submit signup again    No duplicate; safe          High
  Prevention               with same email         normalized email                              existing-account/recovery   
                                                                                                 response                    

  Duplicate     DUP-09     Repeat Phone signup     Account uses verified  Submit phone signup    No duplicate; safe          High
  Prevention               with same phone         phone                  again                  login/recovery response     

  Duplicate     DUP-10     Email case              Existing email differs Signup using different Normalization detects       High
  Prevention               normalization           only by case           casing                 existing account            

  Duplicate     DUP-11     Phone format            Existing phone stored  Signup with equivalent Normalize and detect same   High
  Prevention               normalization           in accepted            accepted format        verified number             
                                                   international format                                                      

  Duplicate     DUP-12     All linked methods      Google, email and      Sign in with each      All methods resolve to same Critical
  Prevention               resolve to one user     phone identities       method and compare     canonical Arogion user ID   
                                                   explicitly linked      user IDs                                           

  Duplicate     DUP-13     Email and phone linked  Email and phone linked Sign in with each      Same canonical user ID      High
  Prevention                                       to one account                                                            

  Duplicate     DUP-14     Google, Email and Phone All three methods      Sign in with each      Same canonical user ID      High
  Prevention               linked                  linked to one account                                                     

  Duplicate     DUP-15     Expired incomplete      Pending signup has     Retry signup           Resume/restart per policy;  Medium
  Prevention               signup retry            expired                                       no unlimited pending        
                                                                                                 records                     

  Duplicate     DUP-16     Signup for              Existing account       Attempt signup with    Do not bypass restriction   High
  Prevention               locked/disabled account locked/disabled        same identity          by creating replacement     
                                                                                                 account                     

  Duplicate     DUP-17     Re-register after       Retention policy       Attempt signup with    Follow documented           High
  Prevention               deletion/deactivation   defined                same identity          retention/re-registration   
                                                                                                 policy                      

  Duplicate     DUP-18     Repeat identical signup Request was already    Retry same request     Idempotent/safe response;   Critical
  Prevention               request                 processed              after timeout          no duplicate user or        
                                                                                                 identity                    

  Google OAuth  GOOG-01    Valid Google ID token   Configured client;     POST token to          Token validates; new signup Critical
                                                   valid ID token         Google-auth endpoint   continues to phone          
                                                                                                 verification                

  Google OAuth  GOOG-02    Already-linked Google   Google sub linked to   Authenticate with same Existing account found; no  High
                           identity                active account         identity               duplicate                   

  Google OAuth  GOOG-03    Malformed ID token      Auth endpoint          Submit malformed token Reject; no account          Critical
                                                   available                                     created/activated           

  Google OAuth  GOOG-04    Invalid signature       Forged test token      Submit token           Reject; no account          Critical
                                                                                                 created/activated           

  Google OAuth  GOOG-05    Expired ID token        Expired test token     Submit token           Reject token                Critical

  Google OAuth  GOOG-06    Wrong audience/client   Token audience differs Submit token           Reject token                Critical
                           ID                                                                                                

  Google OAuth  GOOG-07    Invalid issuer          Token has untrusted    Submit token           Reject token                High
                                                   issuer                                                                    

  Google OAuth  GOOG-08    Missing sub claim       Test token lacks       Submit token           Reject token                High
                                                   subject                                                                   

  Google OAuth  GOOG-09    Invalid nonce when      OIDC nonce mismatch    Submit token           Reject token                High
                           nonce used                                                                                        

  Google OAuth  GOOG-10    Verified Google email   Validated token has    Authenticate           Treat email as verified     High
                                                   email_verified=true                           only after token validation 

  Google OAuth  GOOG-11    Unverified Google email email_verified=false   Authenticate           Do not mark Arogion email   High
                                                                                                 verified from this claim    

  Google OAuth  GOOG-12    Matching email but      Existing account email Authenticate           No silent linking; safe     Critical
                           unlinked sub            matches; Google sub                           conflict or explicit link   
                                                   unlinked                                                                  

  Google OAuth  GOOG-13    Google profile          Same sub remains       Authenticate with      Same account by sub; no new High
                           name/email changes      linked                 updated claims         account                     

  Google OAuth  GOOG-14    Repeat token after      Account exists; token  Repeat auth request    No duplicate; normal        High
                           signup                  still valid                                   validity/login policy       
                                                                                                 applies                     

  Google OAuth  GOOG-15    Google verification     Verifier/key retrieval Attempt auth           Fail safely; never accept   High
                           dependency unavailable  unavailable in test                           unverified token            

  Google OAuth  GOOG-16    Identity belongs to     Linked account locked  Authenticate           Do not issue normal access  High
                           locked account                                                        token                       

  Google OAuth  GOOG-17    Phone verification      New Google signup      Attempt normal         Pending status; no normal   Critical
                           incomplete              pending                login/protected access protected API access        

  Google OAuth  GOOG-18    Password setup          Required password      Attempt final          Keep pending if password is High
                           incomplete              setup incomplete       activation             required                    

  Google OAuth  GOOG-19    Complete Google signup  Google verified; phone Finalize signup        Activate once and issue     Critical
                                                   OTP and required                              Arogion credentials         
                                                   password complete                                                         

  Email Signup  EMAIL-01   Valid unused email      Email not associated   Submit email signup    Create/resume pending       High
                                                   with another account                          signup and initiate         
                                                                                                 verification                

  Email Signup  EMAIL-02   Correct email           Pending signup and     Submit code/link       Mark email verified         High
                           verification            valid code/link                                                           

  Email Signup  EMAIL-03   Incorrect email code    Pending signup; wrong  Submit wrong code      Reject; email remains       High
                                                   code                                          unverified                  

  Email Signup  EMAIL-04   Expired email           Expired code/link      Submit expired         Reject; controlled resend   High
                           verification                                   code/link              if allowed                  

  Email Signup  EMAIL-05   Reuse email             Code already consumed  Submit code again      Reject or safely treat as   High
                           verification code                                                     completed; no duplicates    

  Email Signup  EMAIL-06   Repeated verification   Pending signup         Request email          Apply resend limits and     Medium
                           requests                                       verification           rate limiting               
                                                                          repeatedly                                         

  Email Signup  EMAIL-07   Invalid email format    No valid signup        Submit malformed email Reject before activation    Medium

  Email Signup  EMAIL-08   Email already belongs   Existing verified      Submit signup with     No second account; safe     Critical
                           to verified account     account                same email             recovery/login response     

  Email Signup  EMAIL-09   Email belongs to        Pending transaction    Start signup again     Resume or expire according  High
                           pending signup          exists                                        to policy                   

  Email Signup  EMAIL-10   Email verified, phone   Only email step        Attempt activation     Account remains pending     Critical
                           not verified            complete                                                                  

  Email Signup  EMAIL-11   Email and phone         Required password not  Attempt activation     Account remains pending     High
                           verified, password      set                                                                       
                           missing                                                                                           

  Email Signup  EMAIL-12   Complete email signup   Email and phone        Finalize signup        Activate exactly one        Critical
                                                   verified; valid                               account                     
                                                   password                                                                  

  Email Signup  EMAIL-13   Change email during     Verification underway  Change email           Invalidate/restart old      High
                           signup                                                                verification as appropriate 

  Email Signup  EMAIL-14   Use another person's    Email ownership not    Start signup without   Cannot activate until email High
                           email                   proven                 verifying              is verified                 

  Email Signup  EMAIL-15   Concurrent same-email   Two simultaneous       Send concurrently      No duplicate canonical      Critical
                           signup                  requests with same                            accounts                    
                                                   normalized email                                                          

  Phone Signup  PHONE-01   Valid unused phone      Phone not associated   Submit phone signup    Create/resume pending       High
                                                   with account                                  signup and OTP challenge    

  Phone Signup  PHONE-02   Correct phone OTP       Valid pending          Submit correct OTP     Mark verified and activate  Critical
                                                   challenge                                     phone-only account if       
                                                                                                 policy permits              

  Phone Signup  PHONE-03   Incorrect phone OTP     Wrong OTP              Submit OTP             Reject; no activation       High

  Phone Signup  PHONE-04   Expired phone OTP       Challenge expired      Submit OTP             Reject                      High

  Phone Signup  PHONE-05   Reuse consumed OTP      OTP already used       Submit again           Reject                      Critical

  Phone Signup  PHONE-06   Repeated OTP requests   Pending signup         Request OTP repeatedly Enforce cooldown/rate       High
                                                                                                 limits                      

  Phone Signup  PHONE-07   Exceed OTP attempt      Maximum attempts       Continue guessing      Throttle/lock according to  High
                           limit                   reached                                       policy                      

  Phone Signup  PHONE-08   Invalid phone format    Malformed phone        Submit signup          Reject                      Medium

  Phone Signup  PHONE-09   Equivalent phone        Same number in two     Try alternate format   Normalize and detect same   High
                           formats                 accepted formats                              number                      

  Phone Signup  PHONE-10   Phone already verified  Existing account owns  Attempt new signup     No second account or silent Critical
                           on another account      phone                                         linking                     

  Phone Signup  PHONE-11   Concurrent same-phone   Two requests with same Send concurrently      No duplicate                Critical
                           signup                  phone                                         account/conflicting         
                                                                                                 ownership                   

  Phone Signup  PHONE-12   Restart before OTP      Valid pending          Restart signup         Resume or invalidate        Medium
                           expiry                  challenge                                     challenge per policy        

  Phone Signup  PHONE-13   OTP valid but account   Account                Complete verification  Do not bypass account       High
                           blocked                 disabled/blocked                              restrictions                

  Phone Signup  PHONE-14   Fixed development OTP   Production profile     Try development OTP    Reject predictable          Critical
                           in production           active                 such as 1234           development OTP             

  Phone Signup  PHONE-15   Phone-only signup       Product permits no     Verify phone and       Activate without password   High
                           completes               password               complete signup        per approved policy         

  Password &    PASS-01    Password before Google  Google step incomplete Call password setup    Reject/prevent progression  High
  Activation               verification                                                                                      

  Password &    PASS-02    Password before email   Email step incomplete  Call password setup    Reject/prevent progression  High
  Activation               verification                                                                                      

  Password &    PASS-03    Password before phone   Phone step incomplete  Call password setup    Reject/prevent progression  High
  Activation               verification                                                                                      

  Password &    PASS-04    Valid password after    Required steps         Submit valid password  Store secure hash and       High
  Activation               verifications           complete                                      continue                    

  Password &    PASS-05    Empty password          Signup pending         Submit empty password  Reject                      Medium
  Activation                                                                                                                 

  Password &    PASS-06    Password below minimum  Password policy        Submit too-short       Reject                      Medium
  Activation                                       configured             password                                           

  Password &    PASS-07    Password over maximum   Password policy        Submit oversized       Handle per documented       Medium
  Activation               length                  configured             password               policy                      

  Password &    PASS-08    Password confirmation   Password values differ Submit setup           Reject; do not activate     High
  Activation               mismatch                                                                                          

  Password &    PASS-09    Repeat password setup   Signup already         Repeat request         No duplicate user or        High
  Activation                                       completed                                     unauthorized credential     
                                                                                                 reset                       

  Password &    PASS-10    Password storage hash   Password signup        Inspect test DB safely Raw password never stored   Critical
  Activation                                       completed                                                                 

  Password &    PASS-11    Password set but        Required verification  Attempt activation     Account remains pending     Critical
  Activation               verification missing    incomplete                                                                

  Password &    PASS-12    All required steps      All applicable steps   Finalize signup        Activate once and issue     Critical
  Activation               complete                complete                                      Arogion credentials         

  Password &    PASS-13    Client sets             Request includes       Submit request         Server rejects/ignores      Critical
  Activation               accountStatus ACTIVE    active status                                 client-controlled           
                                                                                                 activation                  

  Password &    PASS-14    Signup expires before   Transaction expired    Call password setup    Reject/restart per policy   Medium
  Activation               password setup                                                                                    

  Password &    PASS-15    Phone-only account      Phone-only policy      Complete phone signup  Phone login works; no fake  High
  Activation               without password        permits no password    and login              password credential         

  Account       LINK-01    Google then Email same  Google account exists; Attempt Email signup   No duplicate; safe          Critical
  Linking                  email                   methods not linked                            recovery/link flow          

  Account       LINK-02    Phone then Email same   Verified phone account Attempt Email signup   No duplicate; prove         Critical
  Linking                  phone                   exists                 with same phone        existing account control    

  Account       LINK-03    Email then Phone same   Email account has      Attempt phone signup   No duplicate; safe          High
  Linking                  phone                   verified phone                                linking/recovery            

  Account       LINK-04    Login with linked       Google identity linked Sign in with Google    Existing account found      High
  Linking                  Google                                                                                            

  Account       LINK-05    Login with              Email account and      Sign in                Existing account found      High
  Linking                  email/password          correct password                                                          

  Account       LINK-06    Login with phone OTP    Phone account exists   Sign in with OTP       Existing account found      High
  Linking                                                                                                                    

  Account       LINK-07    Explicit Google linking User authenticated to  Complete link flow     Link identity to existing   Critical
  Linking                                          existing account and                          user; no new user           
                                                   proves Google identity                                                    

  Account       LINK-08    Link without            Control not proven     Attempt linking        Reject                      Critical
  Linking                  existing-account proof                                                                            

  Account       LINK-09    Google identity already Identity linked to A   Try linking to B       Reject; identity belongs to Critical
  Linking                  linked to user A                                                      one user                    

  Account       LINK-10    Phone already linked to Verified phone belongs User B tries to claim  Reject or approved recovery High
  Linking                  user A                  to A                   it                                                 

  Account       LINK-11    Email already linked to Verified email belongs User B tries to claim  Reject or approved recovery High
  Linking                  user A                  to A                   it                                                 

  Account       LINK-12    Link second login       Both identities proven Complete link flow     Both methods resolve to     High
  Linking                  method                                                                same user ID                

  Account       LINK-13    Cancel link midway      Link process started   Cancel/abandon         Original account preserved; Medium
  Linking                                                                                        no partial conflicting link 

  Account       LINK-14    Repeat linking request  Identity already       Repeat request         No duplicate                High
  Linking                                          linked                                        identity/contact record     

  Account       LINK-15    Unlink only usable      Only one login method  Attempt unlink         Prevent accidental lockout  Medium
  Linking                  method                  exists                                        per policy                  

  Tokens &      TOKEN-01   Token before signup     Required steps         Request Arogion token  No normal authenticated     Critical
  Sessions                 complete                incomplete                                    access                      

  Tokens &      TOKEN-02   Token after signup      Account active         Complete signup/login  Return Arogion token per    High
  Sessions                                                                                       design                      

  Tokens &      TOKEN-03   Valid token on          Token and permission   Call protected         Request succeeds            High
  Sessions                 protected API           valid                  endpoint                                           

  Tokens &      TOKEN-04   Protected API without   Endpoint protected     Call without           401 Unauthorized            Critical
  Sessions                 token                                          Authorization                                      

  Tokens &      TOKEN-05   Malformed Arogion token Protected endpoint     Send malformed token   401 Unauthorized            High
  Sessions                                         available                                                                 

  Tokens &      TOKEN-06   Expired Arogion token   Expired test token     Call endpoint          Reject token                Critical
  Sessions                                                                                                                   

  Tokens &      TOKEN-07   Google access token     Endpoint expects       Send Google access     Reject                      Critical
  Sessions                 used as Arogion token   Arogion token          token                                              

  Tokens &      TOKEN-08   Google ID token used as Endpoint expects       Send Google ID token   Reject unless endpoint      Critical
  Sessions                 Arogion token           Arogion token                                 explicitly validates Google 
                                                                                                 tokens; normal APIs use     
                                                                                                 Arogion credentials         

  Tokens &      TOKEN-09   Refresh valid refresh   Valid refresh token    Call refresh endpoint  Issue new access token per  High
  Sessions                 token                   exists                                        policy                      

  Tokens &      TOKEN-10   Reuse revoked/rotated   Token revoked/rotated  Refresh                Reject and apply session    High
  Sessions                 refresh token                                                         security policy             

  Tokens &      TOKEN-11   Use session after       User logged out        Reuse session/refresh  Invalidated per             High
  Sessions                 logout                                         token                  implementation              

  Tokens &      TOKEN-12   Disabled account has    Account disabled after Call protected API     Reject/revoke as policy     High
  Sessions                 active session          login                                         requires                    

  Tokens &      TOKEN-13   Multiple devices        Account logs in on     Create sessions        Follow session policy       Medium
  Sessions                                         multiple devices                              without duplicate user      

  Tokens &      TOKEN-14   JWT claims validation   Arogion token issued   Inspect test           Issuer, audience, subject,  High
  Sessions                                                                token/validator        expiry and permissions      
                                                                                                 correct                     

  Tokens &      TOKEN-15   Insufficient role       Patient token lacks    Call restricted        403 Forbidden               High
  Sessions                                         required permission    endpoint                                           

  PostgreSQL    DB-01      Database after Google   Google signup          Inspect relevant test  At most one user and one    High
  Integrity                signup                  started/completed      rows                   Google identity, plus       
                                                                                                 required related records    

  PostgreSQL    DB-02      Database after Email    Email signup completed Inspect rows           One user and relevant       High
  Integrity                signup                                                                email/contact/credential    
                                                                                                 records                     

  PostgreSQL    DB-03      Database after Phone    Phone signup completed Inspect rows           One user and verified phone High
  Integrity                signup                                                                record                      

  PostgreSQL    DB-04      Repeat same Google sub  Google identity exists Repeat signup; inspect No second user/external     Critical
  Integrity                                                               DB                     identity                    

  PostgreSQL    DB-05      Repeat normalized email Email exists           Repeat signup; inspect No duplicate normalized     High
  Integrity                                                               DB                     identity/account            

  PostgreSQL    DB-06      Repeat normalized phone Verified phone exists  Repeat signup; inspect No duplicate account for    Critical
  Integrity                                                               DB                     same verified phone         

  PostgreSQL    DB-07      Failed phone            OTP invalid/expired    Inspect account/phone  Phone unverified; account   High
  Integrity                verification                                                          pending                     

  PostgreSQL    DB-08      Failed email            Email code             Inspect account        Email unverified; account   High
  Integrity                verification            invalid/expired                               pending                     

  PostgreSQL    DB-09      Successful completion   All required steps     Inspect account status Activate only at correct    High
  Integrity                                        done                                          transition                  

  PostgreSQL    DB-10      Link method to existing Link flow succeeds     Inspect identity rows  New identity references     High
  Integrity                user                                                                  existing user ID            

  PostgreSQL    DB-11      Concurrent same         Concurrent requests    Inspect identity rows  Unique constraint prevents  Critical
  Integrity                external identity       sent                                          duplicate identity          

  PostgreSQL    DB-12      Failure midway through  Controlled failure     Inspect related rows   Rollback/recovery prevents  Critical
  Integrity                transaction             injected                                      inconsistent active account 

  PostgreSQL    DB-13      Google-only account     Google-only account    Inspect credential     No fake local password      High
  Integrity                credential state        exists                 rows                   credential                  

  PostgreSQL    DB-14      Password storage        Password signup        Inspect test           Only secure hash stored     Critical
  Integrity                                        complete               credential storage                                 

  PostgreSQL    DB-15      OTP challenge state     OTP requested/verified Inspect challenge      Expiry, attempts,           High
  Integrity                                                               metadata               consumption and state       
                                                                                                 consistent                  

  PostgreSQL    DB-16      Session state           Login/refresh/logout   Inspect session        Expiry/revocation state     High
  Integrity                                        performed              records                matches policy              

  PostgreSQL    DB-17      Foreign-key integrity   User/identity records  Run integrity checks   No identity points to       High
  Integrity                                        exist                                         nonexistent user            

  PostgreSQL    DB-18      Roles/application       New account created    Inspect access/role    Only expected default       High
  Integrity                access                                         records                access; no self-assigned    
                                                                                                 privileged role             

  PostgreSQL    DB-19      Retry after timeout     Request may have       Retry and inspect DB   No duplicate; safely        High
  Integrity                                        completed despite                             recoverable response        
                                                   timeout                                                                   

  PostgreSQL    DB-20      Audit log hygiene       Auth tests executed    Inspect logs           Security events recorded    High
  Integrity                                                                                      without raw                 
                                                                                                 tokens/OTPs/passwords       

  Concurrency & CON-01     Concurrent same-email   Two simultaneous       Send both concurrently At most one canonical       Critical
  Resilience               signup                  requests use same                             account                     
                                                   normalized email                                                          

  Concurrency & CON-02     Concurrent same-phone   Two requests use same  Send concurrently      At most one verified        Critical
  Resilience               signup                  phone                                         ownership association       

  Concurrency & CON-03     Concurrent same Google  Two requests use same  Send concurrently      One external identity       Critical
  Resilience               sub                     sub                                           record                      

  Concurrency & CON-04     Concurrent OTP          Same OTP submitted     Send both concurrently Atomic consumption; no      Critical
  Resilience               verification            simultaneously                                inconsistent state          

  Concurrency & CON-05     Concurrent finalization Two final signup       Send concurrently      Activation/credential       Critical
  Resilience                                       requests arrive                               creation safely occurs once 
                                                   together                                                                  

  Concurrency & CON-06     Retry after network     Server may have        Retry same operation   No duplicate user or        High
  Resilience               timeout                 processed request                             conflicting links           

  Concurrency & CON-07     Two users link same     Users A and B attempt  Run attempts           Only one valid association  Critical
  Resilience               Google identity         link                   concurrently                                       

  Concurrency & CON-08     Database failure during Controlled DB failure  Run signup and inspect No partially committed      Critical
  Resilience               signup                                         state                  active account or           
                                                                                                 inconsistent links          

  Security &    SEC-01     Bypass email            Email step incomplete  Call later endpoint    Reject invalid state        Critical
  Abuse                    verification                                   directly               transition                  

  Security &    SEC-02     Bypass phone            Phone step incomplete  Call                   Pending status and access   Critical
  Abuse                    verification                                   activation/protected   denied                      
                                                                          API                                                

  Security &    SEC-03     Tamper with signup      Another test user's    Submit altered ID      Access denied               High
  Abuse                    transaction ID          transaction ID                                                            

  Security &    SEC-04     Reuse another user's    Challenge belongs to   Submit in wrong        Verification fails          High
  Abuse                    OTP challenge           another test user      transaction                                        

  Security &    SEC-05     Brute-force OTP         Repeated incorrect     Exceed attempt limit   Rate limits/attempt         Critical
  Abuse                                            guesses                                       controls apply              

  Security &    SEC-06     Account enumeration in  Known and unknown test Compare responses      No unnecessary              Medium
  Abuse                    password reset          emails                                        account-existence           
                                                                                                 disclosure                  

  Security &    SEC-07     Self-assign admin role  Signup includes        Submit request         Role rejected/ignored       Critical
  Abuse                                            privileged role                                                           

  Security &    SEC-08     Untrusted Google token  Forged/untrusted token Submit to auth         Reject; decoded claims not  Critical
  Abuse                                                                   endpoint               trusted                     

  Security &    SEC-09     Secrets in logs         Run test auth flow     Inspect logs           No raw passwords, tokens,   Critical
  Abuse                                                                                          client secrets or OTPs      

  Security &    SEC-10     Access another user's   User changes target ID Call profile endpoint  Authorization blocks access Critical
  Abuse                    profile                                                                                           

  Security &    SEC-11     Malformed/oversized     Invalid JSON/oversized Call endpoints         Validate and reject safely  High
  Abuse                    requests                values                                                                    

  Security &    SEC-12     Authentication API rate Excessive auth         Repeat login/OTP calls Rate limits and abuse       High
  Abuse                    limits                  requests                                      controls apply              

  Security &    SEC-13     Development OTP in      Production profile     Try fixed development  Rejected                    Critical
  Abuse                    production              active                 OTP                                                

  Security &    SEC-14     Reuse consumed          Token/challenge        Submit again           Reject or safely idempotent High
  Abuse                    signup/verification     consumed                                      per purpose                 
                           token                                                                                             

  Security &    SEC-15     Unauthorized account    Control of both        Attempt link           Reject                      Critical
  Abuse                    linking                 identities not proven                                                     

  End-to-End    E2E-01     Complete new Google     No existing identity;  Google auth -\> phone  One active account, one     Critical
                           signup                  test Google token      OTP -\> password -\>   Google identity, verified   
                                                                          finalize -\> protected phone, password hash if     
                                                                          API                    required, Arogion token     
                                                                                                 works                       

  End-to-End    E2E-02     Complete new Email      Unused test email and  Email signup -\> email One active account and      Critical
                           signup                  phone                  verification -\> phone valid Arogion               
                                                                          OTP -\> password -\>   authentication              
                                                                          finalize -\> protected                             
                                                                          API                                                

  End-to-End    E2E-03     Complete new Phone      Unused phone; policy   Phone signup -\> OTP   One active account; no      Critical
                           signup                  permits no password    -\> protected API      password required under     
                                                                                                 approved policy             

  End-to-End    E2E-04     Cross-method duplicate  Account created with   Attempt other methods; No duplicate user; linked   Critical
                           prevention              one method;            link only through      methods resolve to same     
                                                   email/phone collision  authorized flow        user ID                     
                                                   available                                                                 

  End-to-End    E2E-05     Incomplete signup       Signup stopped before  Call protected         No normal access token;     Critical
                           cannot access API       required step          endpoint               protected endpoint denies   
                                                                                                 access                      

  End-to-End    E2E-06     Completed signup with   Client simulates       Repeat requests after  Safe recovery; no duplicate Critical
                           retries                 timeout/retry          timeout                users or identities         
  -------------------------------------------------------------------------------------------------------------------------------------

## Result recording template

Copy this section for each executed test case:

-   **Test Case ID:**
-   **Status:** Not Run / Pass / Fail / Blocked / Not Applicable
-   **Actual endpoint and method:**
-   **HTTP status:**
-   **Sanitized response summary:**
-   **Database verification:** (record counts/statuses only; no secrets)
-   **Expected vs actual:**
-   **Evidence/reference:**
-   **Defect or next action:**

## Completion criteria

Before considering the authentication service ready, review the recorded
results for: - Duplicate prevention under repeated and concurrent
signup. - Enforcement of Google, email, and phone verification steps. -
Secure account linking without email-only auto-linking. -
Pending-account access restrictions. - Correct separation of Google
tokens and Arogion tokens. - PostgreSQL uniqueness, foreign-key, and
transaction integrity. - Secure password, OTP, refresh-token, and
audit-event handling. - A documented status and evidence for every
applicable test case.
