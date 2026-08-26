// Fordism CI/CD — runs on the native Jenkins on tds-lab (host: jenkins.local).
//   PRs    -> secret-scan + build only (the merge gate)
//   main       -> deploy UAT   (fordism-uat.local, :8088, ADO tag fordism-managed-test)
//   experiment -> deploy EXP   (fordism-exp.local, :8091) — throwaway experiment env
//   v* tag -> deploy PRD   (fordism.local,     :8087, ADO tag fordism-managed) — auto (cutting the tag is the deploy decision)
//
// One image is built (fordism/fordism-*:local) and the SAME artifact is deployed to
// both environments — they differ only by env file (db, port, tag scope, secrets).
// Env files live on the box at $ENVDIR (jenkins-owned, 600) — never in the repo.
pipeline {
  agent any
  options { disableConcurrentBuilds() }
  environment { ENVDIR = '/var/lib/jenkins/fordism-envs' }

  stages {
    stage('Secret scan') {
      steps {
        sh '''
          V=8.18.4
          curl -sSfL "https://github.com/gitleaks/gitleaks/releases/download/v${V}/gitleaks_${V}_linux_x64.tar.gz" | tar -xz gitleaks
          ./gitleaks detect --source . --redact --no-banner --exit-code 1
        '''
      }
    }

    stage('Build') {
      steps {
        // --profile build-only so the agent image is built too: the launcher spawns it per
        // task, so it never appears in `up`, but it still has to exist on the box.
        sh 'docker compose --env-file $ENVDIR/uat.env -p fordism-build --profile build-only build'
      }
    }

    stage('Deploy UAT') {
      when { branch 'main' }
      steps {
        sh '''
          export FORDISM_GIT_SHA="${GIT_COMMIT:-unknown}"
          export FORDISM_VERSION="${TAG_NAME:-${BRANCH_NAME:-dev}}"
          export FORDISM_BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          docker compose -p uat --env-file $ENVDIR/uat.env up -d --remove-orphans
          for i in $(seq 1 40); do
            curl -fsS http://localhost:8088/api/health >/dev/null 2>&1 && { echo ">> UAT healthy"; exit 0; }
            sleep 5
          done
          echo ">> UAT health check failed"; exit 1
        '''
      }
    }

    stage('Deploy EXP') {
      when { branch 'experiment' }
      steps {
        sh '''
          export FORDISM_GIT_SHA="${GIT_COMMIT:-unknown}"
          export FORDISM_VERSION="${TAG_NAME:-${BRANCH_NAME:-dev}}"
          export FORDISM_BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          docker compose -p exp --env-file $ENVDIR/exp.env up -d --remove-orphans
          for i in $(seq 1 40); do
            curl -fsS http://localhost:8091/api/health >/dev/null 2>&1 && { echo ">> EXP healthy"; exit 0; }
            sleep 5
          done
          echo ">> EXP health check failed"; exit 1
        '''
      }
    }

    stage('Deploy PRD') {
      when { buildingTag() }
      steps {
        sh '''
          export FORDISM_GIT_SHA="${GIT_COMMIT:-unknown}"
          export FORDISM_VERSION="${TAG_NAME:-${BRANCH_NAME:-dev}}"
          export FORDISM_BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          docker compose -p prd --env-file $ENVDIR/prd.env up -d --remove-orphans
          for i in $(seq 1 40); do
            curl -fsS http://localhost:8087/api/health >/dev/null 2>&1 && { echo ">> PRD healthy"; exit 0; }
            sleep 5
          done
          echo ">> PRD health check failed"; exit 1
        '''
      }
    }
  }
}
