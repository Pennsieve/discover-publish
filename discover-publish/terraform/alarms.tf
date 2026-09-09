# T1 CloudWatch alarms (EPIC 868m2zvjt; standard sets from
# pennsieve-infra-dashboard/docs/alarm-coverage-plan.md). Keyed by tier —
# this stanza deploys once per publish tier, and alarm names must stay
# unique across them. No alarm_actions yet.
module "service_alarms" {
  source = "git@github.com:Pennsieve/terraform-modules.git//service-alarms"

  environment_name = var.environment_name
  service_name     = var.service_name

  state_machines = {
    (var.tier) = aws_sfn_state_machine.sfn_state_machine.arn
  }
}
