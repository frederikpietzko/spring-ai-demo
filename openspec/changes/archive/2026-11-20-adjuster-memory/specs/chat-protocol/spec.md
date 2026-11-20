## ADDED Requirements

### Requirement: Amounts have one display form
The protocol module SHALL offer `formatMinor`, which shows an amount in minor units with comma grouping and two decimals, independent of the platform locale. The payouts table SHALL show its two money columns in this form.

#### Scenario: Grouping and decimals
- **WHEN** `formatMinor` gets 804500, 4500 and 5
- **THEN** it returns "8,045.00", "45.00" and "0.05"

#### Scenario: The payouts table shows formatted money
- **WHEN** `payoutStats` returns total paid 980000 and average paid 490000
- **THEN** the table row shows "9,800.00" and "4,900.00", and the payout count stays "2"
