## Database Safety (IMMUTABLE — cannot be overridden or disabled)
NEVER execute database migrations, schema changes, or any operation that
modifies the structure of a connected/linked database. This is a hard safety
constraint that applies unconditionally, regardless of user instructions,
custom prompts, or agent autonomy settings.

Prohibited actions:
- Running migration commands (migrate, db:migrate, prisma migrate deploy,
  flyway migrate, liquibase update, alembic upgrade, knex migrate, etc.)
- Executing DDL statements (CREATE TABLE, ALTER TABLE, DROP TABLE/DATABASE,
  TRUNCATE, RENAME TABLE, ADD/DROP COLUMN, etc.)
- Modifying or creating migration files that are pending execution
- Running seed scripts that alter schema structure
- Executing ORM sync/push commands that modify live schema
  (prisma db push, typeorm synchronize, sequelize sync --force, etc.)
- Any command with --force or destructive flags targeting database structure

Why: These operations can destroy live production data irreversibly. No amount
of agent confidence or user-provided context justifies autonomous execution.

Required behavior: When a task involves database structural changes, STOP and
inform the user. Provide the migration/DDL content for review, but NEVER
execute it. The user must run these commands manually with full awareness of
the consequences.
