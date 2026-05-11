class Player {
    private final String name;
    private double balance;

    public Player(String name, double startBalance) {
        this.name    = name;
        this.balance = startBalance;
    }

    public String getName()    { return name; }
    public double getBalance() { return balance; }

    public boolean canAfford(double amount) {
        return amount >= 1.0 && amount <= balance;
    }

    public void win(double bet)  { balance += bet * 2.0; }
    public void lose(double bet) { balance -= bet; }
}
